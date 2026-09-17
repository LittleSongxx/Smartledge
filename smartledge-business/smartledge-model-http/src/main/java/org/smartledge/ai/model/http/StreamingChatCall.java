package org.smartledge.ai.model.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.rag.runtime.model.ChatStreamEvent;
import org.smartledge.ai.rag.runtime.model.ModelCallException;
import reactor.core.publisher.FluxSink;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.*;

/** One logical subscription, one total deadline and one retry owner. JDK body demand stays at one batch.
 * No unbounded Reactor buffer: an exhausted downstream demand fails closed and cancels the HTTP body.
 */
final class StreamingChatCall {
    private final ModelHttpSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final ScheduledExecutorService timer;
    private final FluxSink<ChatStreamEvent> sink;
    private final Map<String, Object> body;
    private ScheduledFuture<?> total, retry;
    private Attempt current;
    private boolean ended, published;
    private int attempts;

    StreamingChatCall(ModelHttpSettings settings, ObjectMapper mapper, HttpClient client,
                      ScheduledExecutorService timer, FluxSink<ChatStreamEvent> sink, Map<String, Object> body) {
        this.settings = settings; this.mapper = mapper; this.client = client;
        this.timer = timer; this.sink = sink; this.body = body;
    }
    synchronized void start() {
        if (ended) { return; }
        try {
            total = timer.schedule(() -> fail(error(TIMEOUT, 0, "total")), settings.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
            attempt();
        } catch (RejectedExecutionException e) { fail(error(LIMIT, 0, "executor")); }
    }
    private synchronized void attempt() {
        if (ended || sink.isCancelled()) { cancel(); return; }
        Attempt next = new Attempt(); current = next; attempts++;
        try {
            byte[] bytes = mapper.writeValueAsBytes(body);
            var endpoint = settings.chat();
            HttpRequest request = HttpRequest.newBuilder(endpoint.uri()).timeout(settings.requestTimeout())
                .header("Authorization", "Bearer " + endpoint.apiKey()).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            next.armIdle("first-response");
            next.future = client.sendAsync(request, info -> {
                synchronized (StreamingChatCall.this) {
                    next.status = info.statusCode();
                    if (next.status < 200 || next.status >= 300) {
                        next.failed(error(next.status == 401 || next.status == 403 ? AUTHENTICATION
                            : next.status == 429 ? RATE_LIMIT : next.status >= 500 ? SERVER : INVALID_REQUEST, next.status, "status"));
                    } else if (!info.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT)
                        .split(";", 2)[0].trim().equals("text/event-stream")) {
                        next.failed(error(INVALID_RESPONSE, next.status, "content-type"));
                    }
                    return next;
                }
            });
            next.future.whenComplete((response, failure) -> {
                if (failure != null) { next.failed(failure); }
            });
            if (next.stopped || ended) { next.release(); }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { fail(error(INVALID_REQUEST, 0, "request")); }
        catch (RuntimeException e) { next.failed(e); }
    }
    synchronized void cancel() {
        if (ended) { return; }
        ended = true; release();
    }
    synchronized void failClosed() { fail(error(CANCELLED, 0, "closed")); }
    private synchronized void fail(ModelCallException error) {
        if (ended) { return; }
        ended = true; release(); sink.error(error);
    }
    private void release() {
        if (total != null) { total.cancel(false); }
        if (retry != null) { retry.cancel(false); }
        if (current != null) { current.release(); }
    }
    private void emit(ChatStreamEvent event) {
        if (ended || sink.isCancelled()) { cancel(); return; }
        if (sink.requestedFromDownstream() == 0) { throw error(BACKPRESSURE, 200, "demand"); }
        // Conservative replay boundary: any protocol fact handed to a consumer forbids retry.
        published = true;
        sink.next(event);
    }
    private final class Attempt implements HttpResponse.BodySubscriber<Void> {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        final ChatSseDecoder decoder = new ChatSseDecoder(mapper, settings, StreamingChatCall.this::emit);
        CompletableFuture<HttpResponse<Void>> future;
        Flow.Subscription subscription;
        ScheduledFuture<?> idle;
        boolean stopped;
        int status;
        long idleVersion;
        @Override public CompletionStage<Void> getBody() { return result; }
        void armIdle(String phase) {
            if (idle != null) { idle.cancel(false); }
            long version = ++idleVersion;
            idle = timer.schedule(() -> {
                synchronized (StreamingChatCall.this) {
                    if (!stopped && !ended && version == idleVersion) { failed(error(TIMEOUT, status, phase)); }
                }
            }, settings.readIdleTimeout().toNanos(), TimeUnit.NANOSECONDS);
        }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            synchronized (StreamingChatCall.this) {
                this.subscription = subscription;
                if (stopped || ended) { subscription.cancel(); return; }
                subscription.request(1);
            }
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            synchronized (StreamingChatCall.this) {
                if (stopped || ended) { return; }
                try {
                    armIdle("read-idle");
                    for (ByteBuffer buffer : buffers) { decoder.accept(buffer); if (ended) { return; } }
                    if (decoder.done()) {
                        stopped = true;
                        ended = true; StreamingChatCall.this.release(); sink.complete();
                    } else { subscription.request(1); }
                } catch (RuntimeException e) { failed(e); }
            }
        }
        @Override public void onComplete() {
            synchronized (StreamingChatCall.this) {
                if (stopped || ended) { return; }
                try {
                    decoder.eof(); stopped = true;
                    ended = true; StreamingChatCall.this.release(); sink.complete();
                } catch (RuntimeException e) { failed(e); }
            }
        }
        @Override public void onError(Throwable failure) { failed(failure); }
        void failed(Throwable failure) {
            synchronized (StreamingChatCall.this) {
                if (stopped || ended || current != this) { return; }
                stopped = true; release();
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) { cause = cause.getCause(); }
                ModelCallException error = cause instanceof ModelCallException typed ? typed
                    : error(cause instanceof HttpTimeoutException ? TIMEOUT : cause instanceof CancellationException ? CANCELLED : TRANSPORT, status, "transport");
                if (!published && error.retryable() && attempts < settings.chatMaxAttempts() && !sink.isCancelled()) {
                    try { retry = timer.schedule(StreamingChatCall.this::attempt,
                        settings.retryBackoff().toNanos() * attempts, TimeUnit.NANOSECONDS); }
                    catch (RejectedExecutionException e) { fail(error(LIMIT, 0, "executor")); }
                } else { fail(error); }
            }
        }
        void release() {
            stopped = true;
            if (idle != null) { idle.cancel(false); }
            if (future != null && !future.isDone()) { future.cancel(true); }
            if (subscription != null) { subscription.cancel(); }
            result.completeExceptionally(error(CANCELLED, status, "released"));
        }
    }
    private static ModelCallException error(ModelCallException.Kind kind, int status, String phase) {
        return new ModelCallException(kind, status, "chat-stream-" + phase);
    }
}
