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

    /**
     * 流式中途落一次已生成答案（崩溃安全兜底）：只更新仍处于 RUNNING 的轮次，
     * 且只覆盖 answer 与 edit_time；终态写入（completeExchange）永远覆盖它。
     *
     * @return false 表示轮次已不存在或已进入终态，本次无需落库。
     */
    boolean flushPartialAnswer(String conversationId, long exchangeId, String answer);

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

    /**
     * 系统作用域探测：其他租户是否已经占用同一 {@code dialogue_code}。
     *
     * <p>文案层不得区分"不存在"与"他租户占用"，但这里必须拒绝创建，避免跨租户碰撞。</p>
     */

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
