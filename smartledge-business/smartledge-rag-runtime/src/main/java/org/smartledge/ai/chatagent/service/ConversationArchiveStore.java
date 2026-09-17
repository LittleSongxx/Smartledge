package org.smartledge.ai.chatagent.service;

import org.smartledge.ai.chatagent.model.ConversationExchangeView;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.ChatTurnStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface ConversationArchiveStore {

    /**
     * 开始一轮问答。
     *
     * @param ownerUserId 会话归属用户 id。仅在**新建会话**时写入；已存在的会话不被改写
     *                    （避免把别人的会话改成自己的），因此入参在更新路径上被忽略。
     */
    ConversationExchangeView startExchange(String conversationId,
                                           Long ownerUserId,
                                           String question,
                                           ChatQueryMode chatMode,
                                           Long selectedDocumentId,
                                           String selectedDocumentName,
                                           KnowledgeBaseSelectionSnapshot knowledgeBaseSelection);

    void refreshSessionScope(String conversationId,
                             ChatQueryMode chatMode,
                             Long selectedDocumentId,
                             String selectedDocumentName,
                             KnowledgeBaseSelectionSnapshot knowledgeBaseSelection);

    void completeExchange(String conversationId,
                          long exchangeId,
                          String answer,
                          List<String> thinkingSteps,
                          List<SearchReference> references,
                          List<String> recommendations,
                          List<String> usedTools,
                          ChatDebugTrace debugTrace,
                          ChatTurnStatus status,
                          String errorMessage,
                          Long firstResponseTimeMs,
                          Long totalResponseTimeMs);

    Optional<ConversationArchiveRecord> getSessionRecord(String conversationId);

    List<ConversationExchangeView> listExchanges(String conversationId);

    List<ConversationExchangeView> listExchangesAfter(String conversationId, long afterExchangeId);

    List<ConversationExchangeView> listRecentExchanges(String conversationId, int limit);

    List<ConversationArchiveRecord> listSessionRecords();

    /**
     * 会话分页。
     *
     * @param ownerUserId 只返回该用户拥有的会话；{@code null} 表示不过滤（仅系统任务使用）。
     *                    传用户 id 时，无归属（0）的历史会话不会出现。
     */
    ConversationArchivePage listSessionRecordPage(int pageNo,
                                                  int pageSize,
                                                  String keyword,
                                                  ChatQueryMode chatMode,
                                                  ChatTurnStatus latestTurnStatus,
                                                  Long ownerUserId);

    /**
     * 会话归属用户 id。
     *
     * @return 空表示会话不存在；值为 0 表示无归属历史会话（按不可访问处理）
     */
    Optional<Long> findOwnerUserId(String conversationId);

    ConversationRemovalResult deleteSession(String conversationId);

    record ConversationArchiveRecord(
        String conversationId,
        boolean running,
        ChatQueryMode chatMode,
        Long selectedDocumentId,
        String selectedDocumentName,
        String knowledgeBaseSelectionMode,
        List<String> selectedKnowledgeBaseIds,
        List<String> selectedKnowledgeBaseNames,
        Instant createdAt,
        Instant updatedAt,
        List<ConversationExchangeView> exchanges
    ) {
    }

    record ConversationRemovalResult(
        int removedDialogueCount,
        int removedExchangeCount
    ) {
    }

    record ConversationArchivePage(
        long pageNo,
        long pageSize,
        long totalSize,
        List<ConversationArchiveRecord> records
    ) {
    }
}
