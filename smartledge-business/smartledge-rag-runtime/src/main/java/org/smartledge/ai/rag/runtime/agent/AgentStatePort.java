package org.smartledge.ai.rag.runtime.agent;

import java.util.List;
import java.util.Optional;
import org.smartledge.ai.rag.runtime.model.ChatMessage;

/** Version + exchange fence applies to every write; no automatic replay of pending work. */
public interface AgentStatePort {
    AgentState begin(String conversationId, long exchangeId);
    AgentState save(AgentState expected, int modelCalls, int toolCalls, List<ChatMessage> completeHistory, boolean checkpoint);
    void finish(AgentState expected);
    Optional<AgentState> get(String conversationId);
    int clear(String conversationId);
}
