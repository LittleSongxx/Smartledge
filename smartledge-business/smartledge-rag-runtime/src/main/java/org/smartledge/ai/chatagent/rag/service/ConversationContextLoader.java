package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.model.memory.ConversationMemoryContext;
import org.smartledge.ai.chatagent.model.memory.ConversationSummaryPayload;
import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.chatagent.rag.model.AnswerHistoryContext;
import org.smartledge.ai.chatagent.rag.model.EvidenceAnchor;
import org.smartledge.ai.chatagent.rag.model.HistoryPlanningContext;
import org.smartledge.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.smartledge.ai.chatagent.service.ConversationMemoryService;
import org.smartledge.ai.chatagent.service.ConversationTraceRecorder;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.enums.ChatQueryMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @description: 会话记忆装载与历史规划上下文组装
 * @author: Song
 **/

@Slf4j
@Service
public class ConversationContextLoader {

    private final ConversationMemoryService conversationMemoryService;
    private final AnswerHistoryContextAssembler answerHistoryContextAssembler;
    private final ConversationEvidenceAnchorService conversationEvidenceAnchorService;
    private final ChatRagProperties properties;

    public ConversationContextLoader(ConversationMemoryService conversationMemoryService,
                                     AnswerHistoryContextAssembler answerHistoryContextAssembler,
                                     ConversationEvidenceAnchorService conversationEvidenceAnchorService,
                                     ChatRagProperties properties) {
        this.conversationMemoryService = conversationMemoryService;
        this.answerHistoryContextAssembler = answerHistoryContextAssembler;
        this.conversationEvidenceAnchorService = conversationEvidenceAnchorService;
        this.properties = properties;
    }

    public ConversationContextBundle load(String conversationId,
                                          String question,
                                          ChatQueryMode chatMode,
                                          ConversationTraceRecorder traceRecorder) {
        ConversationTraceRecorder.StageHandle memoryStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(ConversationTraceStageCode.MEMORY, chatMode == null ? "" : chatMode.name(), "正在装载会话记忆与最近窗口。", null);
        ConversationMemoryContext memoryContext;
        try {
            memoryContext = loadMemoryContext(conversationId, traceRecorder);
            if (traceRecorder != null) {
                traceRecorder.completeStage(memoryStage, "会话记忆装载完成。", Map.of(
                    "compressionApplied", memoryContext != null && memoryContext.isCompressionApplied(),
                    "coveredExchangeId", memoryContext == null ? 0L : memoryContext.getCoveredExchangeId(),
                    "coveredExchangeCount", memoryContext == null ? 0 : memoryContext.getCoveredExchangeCount(),
                    "compressionCount", memoryContext == null ? 0 : memoryContext.getCompressionCount(),
                    "longTermSummary", memoryContext == null ? "" : safeText(memoryContext.getLongTermSummary()),
                    "recentTranscript", memoryContext == null ? "" : safeText(memoryContext.getRecentTranscript()),
                    "answerRecentTranscript", memoryContext == null ? "" : safeText(memoryContext.getAnswerRecentTranscript())
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(memoryStage, "会话记忆装载失败。", exception.getMessage(), null);
            }
            throw exception;
        }

        HistoryPlanningContext historyPlanningContext = buildHistoryPlanningContext(memoryContext);
        String historySummary = buildPlanningHistory(memoryContext, historyPlanningContext);
        List<EvidenceAnchor> recentEvidenceAnchors = loadRecentEvidenceAnchors(conversationId);
        AnswerHistoryContext answerHistoryContext = buildAnswerHistoryContext(
            question,
            memoryContext == null ? "" : memoryContext.getAnswerRecentTranscript(),
            null,
            List.of()
        );

        return ConversationContextBundle.builder()
            .memoryContext(memoryContext)
            .historyPlanningContext(historyPlanningContext)
            .historySummary(historySummary)
            .recentEvidenceAnchors(recentEvidenceAnchors)
            .initialAnswerHistoryContext(answerHistoryContext)
            .build();
    }

    public ConversationMemoryContext loadMemoryContext(String conversationId, ConversationTraceRecorder traceRecorder) {
        return conversationMemoryService.loadMemoryContext(conversationId, traceRecorder);
    }

    private HistoryPlanningContext buildHistoryPlanningContext(ConversationMemoryContext memoryContext) {
        ConversationSummaryPayload payload = memoryContext == null ? null : memoryContext.getSummaryPayload();
        if (payload == null) {
            return HistoryPlanningContext.builder().build();
        }
        return HistoryPlanningContext.builder()
            .conversationGoal(payload.getConversationGoal())
            .stableFacts(payload.getStableFacts() == null ? List.of() : new ArrayList<>(payload.getStableFacts()))
            .pendingQuestions(payload.getPendingQuestions() == null ? List.of() : new ArrayList<>(payload.getPendingQuestions()))
            .retrievalHints(payload.getRetrievalHints() == null ? List.of() : new ArrayList<>(payload.getRetrievalHints()))
            .queryContextHints(payload.getRetrievalHints() == null ? List.of() : new ArrayList<>(payload.getRetrievalHints()))
            .build();
    }

    private String buildPlanningHistory(ConversationMemoryContext memoryContext,
                                        HistoryPlanningContext historyPlanningContext) {
        String structuredHistory = buildStructuredPlanningHistory(historyPlanningContext);
        String recentTranscript = memoryContext == null ? "" : safeText(memoryContext.getRecentTranscript());
        int maxChars = Math.max(1, properties.getPlanningHistoryMaxChars());
        if (recentTranscript.isBlank()) {
            return clipHead(structuredHistory, maxChars);
        }
        int recentBudget = Math.min(Math.max(maxChars / 2, (int) Math.round(maxChars * 0.65D)), maxChars);
        String recentPart = clipTail(recentTranscript, recentBudget);
        int structuredBudget = Math.max(0, maxChars - recentPart.length() - (recentPart.isBlank() ? 0 : 2));
        String structuredPart = clipHead(structuredHistory, structuredBudget);
        return joinNonBlank(structuredPart, recentPart);
    }

    public AnswerHistoryContext buildAnswerHistoryContext(String question,
                                                          String answerRecentTranscript,
                                                          QueryUnderstandingResult queryUnderstanding) {
        return buildAnswerHistoryContext(question, answerRecentTranscript, queryUnderstanding, List.of());
    }

    public AnswerHistoryContext buildAnswerHistoryContext(String question,
                                                          String answerRecentTranscript,
                                                          QueryUnderstandingResult queryUnderstanding,
                                                          List<EvidenceAnchor> recentEvidenceAnchors) {
        return answerHistoryContextAssembler.assemble(question, answerRecentTranscript, queryUnderstanding, recentEvidenceAnchors);
    }

    private List<EvidenceAnchor> loadRecentEvidenceAnchors(String conversationId) {
        if (conversationEvidenceAnchorService == null || StrUtil.isBlank(conversationId)) {
            return List.of();
        }
        try {
            return conversationEvidenceAnchorService.loadRecentEvidenceAnchors(conversationId, 5);
        }
        catch (RuntimeException exception) {
            log.warn("加载上一轮 evidence anchor 失败: conversationId={}, message={}",
                conversationId,
                exception.getMessage(),
                exception);
            return List.of();
        }
    }

    public List<EvidenceAnchor> filterEvidenceAnchors(List<EvidenceAnchor> anchors,
                                                      ChatQueryMode chatMode,
                                                      Long selectedDocumentId,
                                                      KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        if (chatMode == ChatQueryMode.DOCUMENT && selectedDocumentId != null) {
            return anchors.stream()
                .filter(anchor -> anchor != null && Objects.equals(anchor.getDocumentId(), selectedDocumentId))
                .toList();
        }
        if (chatMode == ChatQueryMode.AUTO_DOCUMENT
            && knowledgeBaseSelection != null
            && knowledgeBaseSelection.getAllowedDocumentIds() != null
            && !knowledgeBaseSelection.getAllowedDocumentIds().isEmpty()) {
            return anchors.stream()
                .filter(anchor -> anchor != null && anchor.getDocumentId() != null)
                .filter(anchor -> knowledgeBaseSelection.getAllowedDocumentIds().contains(anchor.getDocumentId()))
                .toList();
        }
        return anchors;
    }

    public void appendAnchorHints(HistoryPlanningContext historyPlanningContext, List<EvidenceAnchor> anchors) {
        if (historyPlanningContext == null || anchors == null || anchors.isEmpty()) {
            return;
        }
        List<String> hints = new ArrayList<>(historyPlanningContext.getQueryContextHints() == null
            ? List.of()
            : historyPlanningContext.getQueryContextHints());
        anchors.stream()
            .map(this::anchorHint)
            .filter(StrUtil::isNotBlank)
            .limit(5)
            .forEach(hints::add);
        historyPlanningContext.setQueryContextHints(hints);
    }

    private String anchorHint(EvidenceAnchor anchor) {
        if (anchor == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendHintPart(builder, "documentId", anchor.getDocumentId());
        appendHintPart(builder, "sectionPath", anchor.getSectionPath());
        appendHintPart(builder, "structureNodeId", anchor.getStructureNodeId());
        appendHintPart(builder, "parentBlockId", anchor.getParentBlockId());
        appendHintPart(builder, "chunkId", anchor.getChunkId());
        return builder.toString().trim();
    }

    private void appendHintPart(StringBuilder builder, String name, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("; ");
        }
        builder.append(name).append('=').append(text);
    }

    private String buildStructuredPlanningHistory(HistoryPlanningContext historyPlanningContext) {
        StringBuilder builder = new StringBuilder();
        if (historyPlanningContext == null) {
            return "";
        }
        appendSection(builder, "会话目标", historyPlanningContext.getConversationGoal());
        appendBulletSection(builder, "已确认事实", historyPlanningContext.getStableFacts());
        appendBulletSection(builder, "待跟进问题", historyPlanningContext.getPendingQuestions());
        appendBulletSection(builder, "检索提示", historyPlanningContext.getRetrievalHints());
        return builder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append("【").append(title).append("】\n").append(content.trim()).append('\n');
    }

    private void appendBulletSection(StringBuilder builder, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append("【").append(title).append("】\n");
        values.stream()
            .filter(item -> item != null && !item.isBlank())
            .limit(5)
            .forEach(item -> builder.append("- ").append(item.trim()).append('\n'));
    }

    private String clipHead(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }

    private String clipTail(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        int start = Math.max(0, normalized.length() - (maxChars - 1));
        return "…" + normalized.substring(start);
    }

    private String joinNonBlank(String left, String right) {
        if (left == null || left.isBlank()) {
            return safeText(right);
        }
        if (right == null || right.isBlank()) {
            return safeText(left);
        }
        return left.trim() + "\n\n" + right.trim();
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
