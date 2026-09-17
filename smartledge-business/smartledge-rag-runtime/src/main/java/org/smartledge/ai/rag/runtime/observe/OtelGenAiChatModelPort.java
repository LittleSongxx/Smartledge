package org.smartledge.ai.rag.runtime.observe;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.smartledge.ai.rag.runtime.model.ChatMessage;
import org.smartledge.ai.rag.runtime.model.ChatResult;
import org.smartledge.ai.rag.runtime.model.ChatStreamEvent;
import org.smartledge.ai.rag.runtime.model.ChatToolDefinition;
import org.smartledge.ai.rag.runtime.port.ChatModelPort;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * OpenTelemetry GenAI 投影。只给 chat 调用打 span，不解释 Plan / evidence / citation。
 */
public final class OtelGenAiChatModelPort implements ChatModelPort {

    static final String TRACER_NAME = "smartledge.genai";

    private final ChatModelPort delegate;
    private final Tracer tracer;

    public OtelGenAiChatModelPort(ChatModelPort delegate) {
        this(delegate, GlobalOpenTelemetry.getTracer(TRACER_NAME));
    }

    OtelGenAiChatModelPort(ChatModelPort delegate, Tracer tracer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public ChatResult call(String systemPrompt, String userPrompt, ChatCallOptions options) {
        Span span = startSpan("chat");
        try (Scope ignored = span.makeCurrent()) {
            ChatResult result = delegate.call(systemPrompt, userPrompt, options);
            recordResult(span, result);
            return result;
        }
        catch (RuntimeException exception) {
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR);
            throw exception;
        }
        finally {
            span.end();
        }
    }

    @Override
    public Flux<ChatStreamEvent> stream(String systemPrompt, String userPrompt, ChatCallOptions options) {
        return traceStream(delegate.stream(systemPrompt, userPrompt, options));
    }

    @Override
    public Flux<ChatStreamEvent> stream(List<ChatMessage> messages, List<ChatToolDefinition> tools, ChatCallOptions options) {
        return traceStream(delegate.stream(messages, tools, options));
    }

    @Override
    public String model() {
        return delegate.model();
    }

    private Flux<ChatStreamEvent> traceStream(Flux<ChatStreamEvent> upstream) {
        return Flux.defer(() -> {
            Span span = startSpan("chat_stream");
            return upstream
                .doOnNext(event -> {
                    if (event != null && event.facts() != null) {
                        recordResult(span, event.facts());
                    }
                })
                .doOnError(error -> {
                    span.recordException(error);
                    span.setStatus(StatusCode.ERROR);
                })
                .doFinally(signal -> span.end());
        });
    }

    private Span startSpan(String operation) {
        return tracer.spanBuilder("gen_ai." + operation)
            .setAttribute("gen_ai.operation.name", operation)
            .setAttribute("gen_ai.request.model", delegate.model() == null ? "" : delegate.model())
            .startSpan();
    }

    private static void recordResult(Span span, ChatResult result) {
        if (result == null) {
            return;
        }
        if (result.model() != null) {
            span.setAttribute("gen_ai.response.model", result.model());
        }
        if (result.finishReason() != null) {
            span.setAttribute("gen_ai.response.finish_reasons", result.finishReason());
        }
        if (result.responseId() != null) {
            span.setAttribute("gen_ai.response.id", result.responseId());
        }
    }
}
