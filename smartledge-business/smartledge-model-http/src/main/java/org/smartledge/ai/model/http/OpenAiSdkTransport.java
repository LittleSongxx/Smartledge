package org.smartledge.ai.model.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.smartledge.ai.rag.runtime.model.ChatMessage;
import org.smartledge.ai.rag.runtime.model.ChatResult;
import org.smartledge.ai.rag.runtime.model.ChatToolDefinition;
import org.smartledge.ai.rag.runtime.model.ModelCallException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.AUTHENTICATION;
import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.INVALID_REQUEST;
import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.INVALID_RESPONSE;
import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.LIMIT;
import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.RATE_LIMIT;
import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.SERVER;
import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.TIMEOUT;
import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.TRANSPORT;

/**
 * Official OpenAI Java SDK transport for sync chat and embedding.
 * Streaming stays on the OpenAI SSE decoder so demand / cancel contracts do not change.
 */
final class OpenAiSdkTransport {

    private final ModelHttpSettings settings;
    private final ObjectMapper mapper;
    private final OpenAIClient chatClient;
    private final OpenAIClient embeddingClient;

    OpenAiSdkTransport(ModelHttpSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
        this.chatClient = clientFor(settings.chat(), settings);
        this.embeddingClient = clientFor(settings.embedding(), settings);
    }

    ChatResult chat(List<ChatMessage> history, List<ChatToolDefinition> tools, ChatCallOptions overrides) {
        try {
            return toResult(chatClient.chat().completions().create(chatParams(history, tools, overrides)));
        }
        catch (RuntimeException exception) {
            throw translate(exception, "chat");
        }
    }

    List<float[]> embed(List<String> texts) {
        EmbeddingCreateParams.Builder builder = EmbeddingCreateParams.builder()
            .model(settings.embedding().model())
            .encodingFormat(EmbeddingCreateParams.EncodingFormat.FLOAT);
        if (texts.size() == 1) {
            builder.input(texts.get(0));
        }
        else {
            builder.inputOfArrayOfStrings(texts);
        }
        try {
            return toVectors(embeddingClient.embeddings().create(builder.build()), texts.size());
        }
        catch (RuntimeException exception) {
            throw translate(exception, "embedding");
        }
    }

    static String sdkBaseUrl(URI endpoint) {
        String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath();
        String stripped = path.replaceAll("/+$", "")
            .replaceAll("/chat/completions$", "")
            .replaceAll("/embeddings$", "");
        String base = endpoint.getScheme() + "://" + endpoint.getAuthority() + stripped;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private ChatCompletionCreateParams chatParams(List<ChatMessage> history,
                                                  List<ChatToolDefinition> tools,
                                                  ChatCallOptions overrides) {
        ChatCallOptions options = overrides == null ? ChatCallOptions.builder().build() : overrides;
        ChatCallOptions defaults = settings.defaults();
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
            .model(firstNonBlank(options.getModel(), settings.chat().model()));
        for (ChatMessage message : history) {
            addMessage(builder, message);
        }
        if (tools != null) {
            for (ChatToolDefinition tool : tools) {
                builder.addTool(toTool(tool));
            }
        }
        Integer maxTokens = first(options.getMaxTokens(), defaults.getMaxTokens());
        if (maxTokens != null) {
            builder.maxTokens(maxTokens.longValue());
        }
        Double temperature = first(options.getTemperature(), defaults.getTemperature());
        if (temperature != null) {
            builder.temperature(temperature);
        }
        Double topP = first(options.getTopP(), defaults.getTopP());
        if (topP != null) {
            builder.topP(topP);
        }
        addOptional(builder, settings.thinkingField(), first(options.getThinking(), defaults.getThinking()));
        addOptional(builder, "reasoning_effort", first(options.getReasoningEffort(), defaults.getReasoningEffort()));
        addOptional(builder, "verbosity", first(options.getVerbosity(), defaults.getVerbosity()));
        settings.chatExtensions().forEach((key, value) -> addOptional(builder, key, value));
        return builder.build();
    }

    private static void addMessage(ChatCompletionCreateParams.Builder builder, ChatMessage message) {
        switch (message.role()) {
            case "system" -> builder.addMessage(
                ChatCompletionSystemMessageParam.builder().content(message.content()).build());
            case "user" -> builder.addMessage(
                ChatCompletionUserMessageParam.builder().content(message.content()).build());
            case "tool" -> builder.addMessage(
                ChatCompletionToolMessageParam.builder()
                    .toolCallId(message.toolCallId())
                    .content(message.content())
                    .build());
            default -> builder.addMessage(assistantMessage(message));
        }
    }

    private static ChatCompletionAssistantMessageParam assistantMessage(ChatMessage message) {
        ChatCompletionAssistantMessageParam.Builder builder = ChatCompletionAssistantMessageParam.builder();
        if (message.content() != null && !message.content().isBlank()) {
            builder.content(message.content());
        }
        for (ChatResult.ToolCall call : message.toolCalls()) {
            builder.addToolCall(ChatCompletionMessageToolCall.builder()
                .id(call.id())
                .function(ChatCompletionMessageToolCall.Function.builder()
                    .name(call.name())
                    .arguments(call.arguments())
                    .build())
                .build());
        }
        return builder.build();
    }

    private ChatCompletionTool toTool(ChatToolDefinition tool) {
        return ChatCompletionTool.builder()
            .function(FunctionDefinition.builder()
                .name(tool.name())
                .description(tool.description() == null ? "" : tool.description())
                .parameters(toFunctionParameters(tool.parameters()))
                .build())
            .build();
    }

    private FunctionParameters toFunctionParameters(Map<String, Object> parameters) {
        FunctionParameters.Builder builder = FunctionParameters.builder();
        JsonNode node = mapper.valueToTree(parameters == null ? Map.of() : parameters);
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                builder.putAdditionalProperty(field.getKey(), JsonValue.fromJsonNode(field.getValue()));
            }
        }
        return builder.build();
    }

    private ChatResult toResult(ChatCompletion completion) {
        if (completion.choices() == null || completion.choices().size() != 1
            || blank(completion.id()) || blank(completion.model())) {
            throw failure(INVALID_RESPONSE, 200, "chat");
        }
        ChatCompletion.Choice choice = completion.choices().get(0);
        if (choice.message() == null || choice.finishReason() == null) {
            throw failure(INVALID_RESPONSE, 200, "chat");
        }
        String text = choice.message().content().orElse("");
        List<ChatResult.ToolCall> calls = new ArrayList<>();
        for (ChatCompletionMessageToolCall toolCall : choice.message().toolCalls().orElse(List.of())) {
            if (toolCall == null || toolCall.function() == null
                || blank(toolCall.id()) || blank(toolCall.function().name())
                || toolCall.function().arguments() == null) {
                throw failure(INVALID_RESPONSE, 200, "chat-tool");
            }
            String arguments = toolCall.function().arguments();
            if (arguments.getBytes(StandardCharsets.UTF_8).length > settings.maxToolArgumentBytes()) {
                throw failure(LIMIT, 200, "chat-tool");
            }
            calls.add(new ChatResult.ToolCall(toolCall.id(), toolCall.function().name(), arguments));
        }
        if (text.isBlank() && calls.isEmpty()) {
            throw failure(INVALID_RESPONSE, 200, "chat");
        }
        ChatResult.Usage usage = completion.usage().map(item -> new ChatResult.Usage(
            toInt(item.promptTokens()),
            toInt(item.completionTokens()),
            toInt(item.totalTokens())
        )).orElse(null);
        return new ChatResult(text, completion.id(), completion.model(), choice.finishReason().asString(), usage, calls);
    }

    private List<float[]> toVectors(CreateEmbeddingResponse response, int expected) {
        if (response.data() == null || response.data().size() != expected) {
            throw failure(INVALID_RESPONSE, 200, "embedding");
        }
        float[][] ordered = new float[expected][];
        for (var item : response.data()) {
            long indexValue = item.index();
            if (indexValue < 0 || indexValue >= expected || ordered[(int) indexValue] != null) {
                throw failure(INVALID_RESPONSE, 200, "embedding-index");
            }
            List<Float> embedding = item.embedding();
            if (embedding == null || embedding.size() != settings.embeddingDimensions()) {
                throw failure(INVALID_RESPONSE, 200, "embedding-dimensions");
            }
            float[] values = new float[embedding.size()];
            for (int offset = 0; offset < values.length; offset++) {
                Float number = embedding.get(offset);
                if (number == null || !Float.isFinite(number)) {
                    throw failure(INVALID_RESPONSE, 200, "embedding-value");
                }
                values[offset] = number;
            }
            ordered[(int) indexValue] = values;
        }
        for (float[] vector : ordered) {
            if (vector == null) {
                throw failure(INVALID_RESPONSE, 200, "embedding-index");
            }
        }
        return List.of(ordered);
    }

    private static OpenAIClient clientFor(ModelHttpSettings.Endpoint endpoint, ModelHttpSettings settings) {
        return OpenAIOkHttpClient.builder()
            .apiKey(endpoint.apiKey())
            .baseUrl(sdkBaseUrl(endpoint.uri()))
            .timeout(settings.requestTimeout())
            .maxRetries(0)
            .build();
    }

    private static void addOptional(ChatCompletionCreateParams.Builder builder, String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        builder.putAdditionalBodyProperty(key, JsonValue.from(value));
    }

    private static ModelCallException translate(RuntimeException exception, String phase) {
        if (exception instanceof ModelCallException failure) {
            return failure;
        }
        if (exception instanceof UnauthorizedException) {
            return failure(AUTHENTICATION, 401, phase);
        }
        if (exception instanceof RateLimitException) {
            return failure(RATE_LIMIT, 429, phase);
        }
        if (exception instanceof UnexpectedStatusCodeException status) {
            int code = status.statusCode();
            ModelCallException.Kind kind = code == 401 || code == 403 ? AUTHENTICATION
                : code == 429 ? RATE_LIMIT
                : code >= 500 ? SERVER
                : INVALID_REQUEST;
            return failure(kind, code, phase);
        }
        if (exception instanceof OpenAIException) {
            String message = Optional.ofNullable(exception.getMessage()).orElse("");
            if (message.toLowerCase().contains("timeout")) {
                return failure(TIMEOUT, 0, phase);
            }
            return failure(TRANSPORT, 0, phase);
        }
        throw exception;
    }

    private static Integer toInt(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw failure(INVALID_RESPONSE, 200, "chat-usage");
        }
        return (int) value;
    }

    private static <T> T first(T override, T fallback) {
        return override == null ? fallback : override;
    }

    private static String firstNonBlank(String override, String fallback) {
        return override == null || override.isBlank() ? fallback : override;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ModelCallException failure(ModelCallException.Kind kind, int status, String phase) {
        return new ModelCallException(kind, status, phase);
    }
}
