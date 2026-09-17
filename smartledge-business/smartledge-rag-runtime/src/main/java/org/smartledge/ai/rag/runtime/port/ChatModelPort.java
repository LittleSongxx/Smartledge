package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.smartledge.ai.rag.runtime.model.ChatResult;
import org.smartledge.ai.rag.runtime.model.ChatStreamEvent;
import reactor.core.publisher.Flux;
import java.util.List;
import org.smartledge.ai.rag.runtime.model.ChatMessage;
import org.smartledge.ai.rag.runtime.model.ChatToolDefinition;

/** Shared chat input contract. Interrupting a call or cancelling a stream releases its request. */
public interface ChatModelPort {
    ChatResult call(String systemPrompt, String userPrompt, ChatCallOptions options);
    Flux<ChatStreamEvent> stream(
        String systemPrompt, String userPrompt, ChatCallOptions options);
    Flux<ChatStreamEvent> stream(List<ChatMessage> messages, List<ChatToolDefinition> tools, ChatCallOptions options);
    String model();
}
