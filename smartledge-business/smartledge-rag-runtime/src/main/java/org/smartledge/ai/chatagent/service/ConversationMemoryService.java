package org.smartledge.ai.chatagent.service;

import org.smartledge.ai.chatagent.model.ConversationMemorySummaryView;
import org.smartledge.ai.chatagent.model.memory.ConversationMemoryContext;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface ConversationMemoryService {

    ConversationMemoryContext loadMemoryContext(String conversationId);

    default ConversationMemoryContext loadMemoryContext(String conversationId, ConversationTraceRecorder traceRecorder) {
        return loadMemoryContext(conversationId);
    }

    void refreshConversationSummaryAsync(String conversationId);

    ConversationMemorySummaryView getConversationSummary(String conversationId);

    ConversationMemorySummaryView rebuildConversationSummary(String conversationId);

    void deleteConversationSummary(String conversationId);
}
