package org.smartledge.ai.chatagent.service;

import org.smartledge.ai.chatagent.model.memory.LongTermMemoryFact;

import java.util.List;

/**
 * Conversation-scoped long-term facts. The store never silently overwrites an ACTIVE row.
 */
public interface LongTermMemoryStore {

    List<LongTermMemoryFact> listActiveFacts(String conversationId, int limit);

    LongTermMemoryFact rememberExplicit(String conversationId,
                                        Long userId,
                                        String entityKey,
                                        String factText,
                                        long exchangeId);
}
