package org.smartledge.ai.rag.runtime.observe;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.smartledge.ai.rag.runtime.model.ChatResult;
import org.smartledge.ai.rag.runtime.model.ChatStreamEvent;
import org.smartledge.ai.rag.runtime.model.ChatMessage;
import org.smartledge.ai.rag.runtime.model.ChatToolDefinition;
import org.smartledge.ai.rag.runtime.port.ChatModelPort;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OtelGenAiChatModelPortTest {

    @Test
    @DisplayName("包装后仍把 call 原样交给下游，不改 ChatResult")
    void delegatesCallUnchanged() {
        AtomicInteger calls = new AtomicInteger();
        ChatResult expected = new ChatResult("ok", "id-1", "demo-model", "stop", null, List.of());
        ChatModelPort inner = new ChatModelPort() {
            @Override
            public ChatResult call(String systemPrompt, String userPrompt, ChatCallOptions options) {
                calls.incrementAndGet();
                return expected;
            }

            @Override
            public Flux<ChatStreamEvent> stream(String systemPrompt, String userPrompt, ChatCallOptions options) {
                return Flux.empty();
            }

            @Override
            public Flux<ChatStreamEvent> stream(List<ChatMessage> messages, List<ChatToolDefinition> tools,
                                                ChatCallOptions options) {
                return Flux.empty();
            }

            @Override
            public String model() {
                return "demo-model";
            }
        };
        Tracer tracer = TracerProvider.noop().get("test");
        OtelGenAiChatModelPort port = new OtelGenAiChatModelPort(inner, tracer);

        ChatResult actual = port.call("sys", "user", ChatCallOptions.builder().build());

        assertThat(actual).isSameAs(expected);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(port.model()).isEqualTo("demo-model");
    }
}
