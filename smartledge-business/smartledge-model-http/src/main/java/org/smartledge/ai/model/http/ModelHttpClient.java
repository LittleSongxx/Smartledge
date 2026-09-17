package org.smartledge.ai.model.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.smartledge.ai.rag.runtime.model.ChatResult;
import org.smartledge.ai.rag.runtime.model.ChatMessage;
import org.smartledge.ai.rag.runtime.model.ChatToolDefinition;
import org.smartledge.ai.rag.runtime.model.ModelCallException;
import org.smartledge.ai.rag.runtime.port.ChatModelPort;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.*;

/** Owns model wire protocols, bounded response subscription and cancellation; no business state or routing. */
public final class ModelHttpClient implements AutoCloseable {
    private final ModelHttpSettings settings;
    private final ObjectMapper mapper;
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private volatile HttpClient client;
    private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
    private final Set<StreamingChatCall> streams = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final OpenAiSdkTransport sdk;
    private final ChatModelPort chat = new ChatModelPort() {
        @Override public ChatResult call(String system, String user, ChatCallOptions options) {
            return callChat(system, user, options);
        }
        @Override public reactor.core.publisher.Flux<org.smartledge.ai.rag.runtime.model.ChatStreamEvent> stream(
                String system, String user, ChatCallOptions options) {
            return stream(promptMessages(system, user), List.of(), options);
        }
        @Override public reactor.core.publisher.Flux<org.smartledge.ai.rag.runtime.model.ChatStreamEvent> stream(
                List<ChatMessage> messages, List<ChatToolDefinition> tools, ChatCallOptions options) {
            return reactor.core.publisher.Flux.create(sink -> {
                if (closed.get()) { sink.error(failure(CANCELLED, 0, "chat-stream")); return; }
                var call = new StreamingChatCall(settings, mapper, client, timer, sink,
                    chatBody(messages, tools, options, true));
                streams.add(call);
                sink.onDispose(() -> { call.cancel(); streams.remove(call); });
                if (closed.get()) { call.failClosed(); } else { call.start(); }
            }, reactor.core.publisher.FluxSink.OverflowStrategy.ERROR);
        }
        @Override public String model() { return settings.chat().model(); }
    };
    private final EmbeddingPort embedding = new EmbeddingPort() {
        @Override public List<float[]> embed(List<String> texts) { return callEmbedding(texts); }
        @Override public String model() { return settings.embedding().model(); }
    };

    public ModelHttpClient(ModelHttpSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.workers = new ThreadPoolExecutor(settings.workerThreads(), settings.workerThreads(), 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), runnable -> daemon(runnable, "model-http"), new ThreadPoolExecutor.AbortPolicy());
        var scheduler = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "model-http-timeout"));
        scheduler.setRemoveOnCancelPolicy(true);
        this.timer = scheduler;
        this.client = HttpClient.newBuilder().executor(workers).connectTimeout(settings.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        this.sdk = new OpenAiSdkTransport(settings, this.mapper);
    }
    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
    }
    public ChatModelPort chat() { return chat; }
    public EmbeddingPort embedding() { return embedding; }

    private Map<String, Object> chatBody(String system, String user, ChatCallOptions overrides, boolean streaming) {
        return chatBody(promptMessages(system, user), List.of(), overrides, streaming);
    }
    private static List<ChatMessage> promptMessages(String system, String user) {
        List<ChatMessage> result = new ArrayList<>();
        if (system != null && !system.isBlank()) result.add(ChatMessage.text("system", system));
        result.add(ChatMessage.text("user", user == null || user.isBlank() ? "" : user));
        return result;
    }
    private Map<String, Object> chatBody(List<ChatMessage> history, List<ChatToolDefinition> tools,
                                        ChatCallOptions overrides, boolean streaming) {
        ChatCallOptions options = overrides == null ? ChatCallOptions.builder().build() : overrides;
        ChatCallOptions defaults = settings.defaults();
        Map<String, Object> body = new LinkedHashMap<>(settings.chatExtensions());
        body.put("model", value(options.getModel(), settings.chat().model()));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage message : history) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", message.role()); wire.put("content", message.content());
            if (message.toolCallId() != null) wire.put("tool_call_id", message.toolCallId());
            if (!message.toolCalls().isEmpty()) wire.put("tool_calls", message.toolCalls().stream().map(call ->
                Map.of("id", call.id(), "type", "function", "function", Map.of("name", call.name(), "arguments", call.arguments()))).toList());
            messages.add(wire);
        }
        body.put("messages", messages);
        if (!tools.isEmpty()) body.put("tools", tools.stream().map(tool -> Map.of("type", "function", "function",
            Map.of("name", tool.name(), "description", tool.description(), "parameters", tool.parameters()))).toList());
        body.put("stream", streaming);
        if (streaming) { body.put("stream_options", Map.of("include_usage", true)); }
        add(body, "max_tokens", value(options.getMaxTokens(), defaults.getMaxTokens()));
        add(body, "temperature", value(options.getTemperature(), defaults.getTemperature()));
        add(body, "top_p", value(options.getTopP(), defaults.getTopP()));
        add(body, settings.thinkingField(), value(options.getThinking(), defaults.getThinking()));
        add(body, "reasoning_effort", value(options.getReasoningEffort(), defaults.getReasoningEffort()));
        add(body, "verbosity", value(options.getVerbosity(), defaults.getVerbosity()));
        return body;
    }

    private ChatResult callChat(String system, String user, ChatCallOptions overrides) {
        checkCancellation("chat");
        return sdk.chat(promptMessages(system, user), List.of(), overrides);
    }

    private List<float[]> callEmbedding(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Embedding requires non-null input texts");
        }
        checkCancellation("embedding");
        return sdk.embed(texts);
    }

    private JsonNode request(ModelHttpSettings.Endpoint endpoint, Map<String, Object> body, int attempts, String phase) {
        byte[] bytes;
        try { bytes = mapper.writeValueAsBytes(body); }
        catch (JsonProcessingException exception) { throw failure(INVALID_REQUEST, 0, phase); }
        long deadline = System.nanoTime() + settings.requestTimeout().toNanos();
        for (int attempt = 1; ; attempt++) {
            checkCancellation(phase);
            try { return exchange(endpoint, bytes, deadline, phase); }
            catch (ModelCallException failure) {
                checkCancellation(phase);
                if (!failure.retryable() || attempt >= attempts) { throw failure; }
                long backoff = settings.retryBackoff().toNanos() * attempt;
                if (deadline - System.nanoTime() <= backoff) { throw failure(TIMEOUT, 0, phase); }
                try { TimeUnit.NANOSECONDS.sleep(backoff); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw failure(CANCELLED, 0, phase); }
            }
        }
    }

    private JsonNode exchange(ModelHttpSettings.Endpoint endpoint, byte[] bytes, long deadline, String phase) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { throw failure(TIMEOUT, 0, phase); }
        HttpRequest request = HttpRequest.newBuilder(endpoint.uri()).timeout(java.time.Duration.ofNanos(remaining))
            .header("Authorization", "Bearer " + endpoint.apiKey()).header("Content-Type", "application/json")
            .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        java.util.concurrent.atomic.AtomicReference<BoundedBody> bodyRef = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        HttpClient currentClient = client;
        if (currentClient == null) { throw failure(CANCELLED, 0, phase); }
        CompletableFuture<HttpResponse<byte[]>> future = currentClient.sendAsync(request, info -> {
            BoundedBody subscriber = new BoundedBody(settings.maxResponseBytes(), phase, info.statusCode());
            bodyRef.set(subscriber);
            if (cancelled.get() || closed.get()) { subscriber.abort(); }
            return subscriber;
        });
        active.add(future);
        try {
            checkCancellation(phase);
            HttpResponse<byte[]> response = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw failure(status == 401 || status == 403 ? AUTHENTICATION : status == 429 ? RATE_LIMIT
                    : status >= 500 ? SERVER : INVALID_REQUEST, status, phase);
            }
            if (response.body().length == 0) { throw failure(INVALID_RESPONSE, status, phase); }
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject()) { throw failure(INVALID_RESPONSE, status, phase); }
            return json;
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw failure(CANCELLED, 0, phase); }
        catch (TimeoutException timeout) { throw failure(TIMEOUT, 0, phase); }
        catch (CancellationException cancellation) { throw failure(CANCELLED, 0, phase); }
        catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ModelCallException failure) { throw failure; }
            throw failure(cause instanceof java.net.http.HttpTimeoutException ? TIMEOUT : TRANSPORT, 0, phase);
        }
        catch (java.io.IOException exception) { throw failure(INVALID_RESPONSE, 200, phase); }
        finally {
            cancelled.set(true);
            future.cancel(true);
            BoundedBody subscriber = bodyRef.get();
            if (subscriber != null) { subscriber.abort(); }
            active.remove(future);
        }
    }

    private final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private final String phase;
        private final int status;
        private Flow.Subscription subscription;
        private ScheduledFuture<?> idle;
        private long readVersion;
        BoundedBody(int limit, String phase, int status) { this.limit = limit; this.phase = phase; this.status = status; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (result.isDone() || closed.get()) { abort(); subscription.cancel(); return; }
            resetIdle(); subscription.request(1);
        }
        private void resetIdle() {
            if (idle != null) { idle.cancel(false); }
            long version = ++readVersion;
            idle = timer.schedule(() -> {
                synchronized (this) {
                    if (version == readVersion && !result.isDone()) { onError(failure(TIMEOUT, 0, phase + "-read")); }
                }
            }, settings.readIdleTimeout().toNanos(), TimeUnit.NANOSECONDS);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) { return; }
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) { onError(failure(LIMIT, status, phase)); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            resetIdle(); subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) {
            if (idle != null) { idle.cancel(false); }
            if (subscription != null) { subscription.cancel(); }
            result.completeExceptionally(error);
        }
        synchronized void abort() {
            if (result.isDone()) { return; }
            if (idle != null) { idle.cancel(false); }
            if (subscription != null) { subscription.cancel(); }
            if (!result.isDone()) { result.completeExceptionally(failure(CANCELLED, 0, phase)); }
        }
        @Override public synchronized void onComplete() {
            if (idle != null) { idle.cancel(false); }
            result.complete(bytes.toByteArray());
        }
    }

    private void checkCancellation(String phase) {
        if (closed.get() || Thread.currentThread().isInterrupted()) { throw failure(CANCELLED, 0, phase); }
    }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            streams.forEach(StreamingChatCall::failClosed);
            active.forEach(future -> future.cancel(true));
            workers.shutdownNow(); timer.shutdownNow();
            // JDK 17 has no HttpClient.close(); release its pool owner after cancelling every active exchange.
            client = null;
        }
    }
    private static <T> T value(T override, T fallback) { return override == null ? fallback : override; }
    private static void add(Map<String, Object> body, String key, Object value) { if (value != null) { body.put(key, value); } }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ModelCallException failure(ModelCallException.Kind kind, int status, String phase) { return new ModelCallException(kind, status, phase); }
    private static Integer token(JsonNode usage, String key) {
        JsonNode value = usage.get(key);
        if (value == null || value.isNull()) { return null; }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) { throw failure(INVALID_RESPONSE, 200, "chat-usage"); }
        return value.intValue();
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatWire(String id, String model, List<ChoiceWire> choices, JsonNode usage) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChoiceWire(MessageWire message, @JsonProperty("finish_reason") String finishReason) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MessageWire(JsonNode content, @JsonProperty("tool_calls") List<ToolWire> toolCalls) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ToolWire(String id, String type, FunctionWire function) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FunctionWire(String name, String arguments) { }
}
