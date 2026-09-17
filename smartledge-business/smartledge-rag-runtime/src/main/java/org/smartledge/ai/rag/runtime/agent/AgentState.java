package org.smartledge.ai.rag.runtime.agent;

import java.util.List;
import org.smartledge.ai.rag.runtime.model.ChatMessage;

/** Committed replayable history and independently durable dispatch counters. */
public record AgentState(String conversationId, long exchangeId, long version, long checkpointCount,
                         int modelCalls, int toolCalls, List<ChatMessage> messages) {
    public AgentState { messages = List.copyOf(messages); }
}
