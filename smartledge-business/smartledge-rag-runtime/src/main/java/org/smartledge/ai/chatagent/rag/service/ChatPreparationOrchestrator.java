package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.model.memory.ConversationMemoryContext;
import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.smartledge.ai.chatagent.model.trace.RouteTraceSnapshot;
import org.smartledge.ai.chatagent.model.trace.RouteTraceSnapshot.Substage;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.chatagent.rag.model.AnswerHistoryContext;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.smartledge.ai.chatagent.rag.model.EvidenceAnchor;
import org.smartledge.ai.chatagent.rag.model.ExecutionMode;
import org.smartledge.ai.chatagent.rag.model.HistoryPlanningContext;
import org.smartledge.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.smartledge.ai.chatagent.rag.model.RagRewriteResult;
import org.smartledge.ai.chatagent.rag.model.RetrievalIntent;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.model.StructureNavigationIntent;
import org.smartledge.ai.chatagent.rag.model.StructureNavigationResult;
import org.smartledge.ai.rag.runtime.model.DocumentStructureNode;
import org.smartledge.ai.chatagent.service.ConversationTraceRecorder;
import org.smartledge.ai.chatagent.service.TaskInfo;
import org.smartledge.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: 服务层
 * @author: Song
 **/

@Slf4j
@Service
public class ChatPreparationOrchestrator {

    private final ChatRagProperties properties;
    private final ChatQueryRewriteService chatQueryRewriteService;
    private final DocumentQuestionRouter documentQuestionRouter;
    private final StructureNavigationResolver structureNavigationResolver;
    private final RetrievalPlanAssembler retrievalPlanAssembler;
    private final ConversationContextLoader conversationContextLoader;
    private final KnowledgeBoundaryResolver knowledgeBoundaryResolver;
    private final KnowledgeRoutePlanner knowledgeRoutePlanner;
    private final NoEvidenceReplyPolicy noEvidenceReplyPolicy;

    public ChatPreparationOrchestrator(ChatRagProperties properties,
                                       ChatQueryRewriteService chatQueryRewriteService,
                                       DocumentQuestionRouter documentQuestionRouter,
                                       StructureNavigationResolver structureNavigationResolver,
                                       RetrievalPlanAssembler retrievalPlanAssembler,
                                       ConversationContextLoader conversationContextLoader,
                                       KnowledgeBoundaryResolver knowledgeBoundaryResolver,
                                       KnowledgeRoutePlanner knowledgeRoutePlanner,
                                       NoEvidenceReplyPolicy noEvidenceReplyPolicy) {
        this.properties = properties;
        this.chatQueryRewriteService = chatQueryRewriteService;
        this.documentQuestionRouter = documentQuestionRouter;
        this.structureNavigationResolver = structureNavigationResolver;
        this.retrievalPlanAssembler = retrievalPlanAssembler;
        this.conversationContextLoader = conversationContextLoader;
        this.knowledgeBoundaryResolver = knowledgeBoundaryResolver;
        this.knowledgeRoutePlanner = knowledgeRoutePlanner;
        this.noEvidenceReplyPolicy = noEvidenceReplyPolicy;
    }

    public ConversationExecutionPlan prepare(TaskInfo taskInfo) {
        String conversationId = taskInfo.conversationId();
        String question = taskInfo.question();
        ChatQueryMode chatMode = taskInfo.chatMode();
        Long selectedDocumentId = taskInfo.selectedDocumentId();
        String selectedDocumentName = taskInfo.selectedDocumentName();
        Long selectedTaskId = taskInfo.selectedTaskId();
        KnowledgeBaseSelectionSnapshot knowledgeBaseSelection = taskInfo.knowledgeBaseSelectionSnapshot();
        LocalDate currentDate = taskInfo.currentDate();
        String currentDateText = taskInfo.currentDateText();
        ConversationTraceRecorder traceRecorder = taskInfo.traceRecorder();

        ConversationContextBundle ctx = conversationContextLoader.load(conversationId, question, chatMode, traceRecorder);
        ConversationMemoryContext memoryContext = ctx.getMemoryContext();
        HistoryPlanningContext historyPlanningContext = ctx.getHistoryPlanningContext();
        String historySummary = ctx.getHistorySummary();
        List<EvidenceAnchor> recentEvidenceAnchors = ctx.getRecentEvidenceAnchors();
        AnswerHistoryContext answerHistoryContext = ctx.getInitialAnswerHistoryContext();

        boolean requiresCurrentDateAnchoring = TimeSensitiveQueryHelper.requiresCurrentDateAnchoring(question);
        boolean requiresFreshSearch = TimeSensitiveQueryHelper.requiresFreshSearch(question);
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }

        if (chatMode == ChatQueryMode.OPEN_CHAT) {
            ConversationExecutionPlan plan = basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .mode(ExecutionMode.REACT_AGENT)
                .build();
            if (traceRecorder != null) {
                Map<String, Object> routeSnapshot = RouteTraceSnapshot.of(Substage.OPEN_CHAT_EXECUTION, Map.of(
                    "chatMode", chatMode.name(),
                    "executionMode", ExecutionMode.REACT_AGENT.name(),
                    "requiresFreshSearch", requiresFreshSearch,
                    "requiresCurrentDateAnchoring", requiresCurrentDateAnchoring
                ));
                ConversationTraceRecorder.StageHandle routeStage = traceRecorder.startStage(
                    ConversationTraceStageCode.ROUTE,
                    ExecutionMode.REACT_AGENT.name(),
                    "路由到开放式 Agent。",
                    routeSnapshot
                );
                traceRecorder.completeStage(routeStage, "已判定走开放式 Agent 路径。", routeSnapshot);
            }
            return plan;
        }
        if (knowledgeBoundaryResolver.selectionMode(knowledgeBaseSelection) == KnowledgeBaseSelectionMode.NONE) {
            return basePlan(question, ChatQueryMode.OPEN_CHAT, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .mode(ExecutionMode.REACT_AGENT)
                .build();
        }

        if (!properties.isEnabled()) {
            throw new IllegalStateException("当前文档问答模式未启用，请先开启聊天侧 RAG 编排");
        }
        if (chatMode == ChatQueryMode.DOCUMENT && (selectedDocumentId == null || selectedTaskId == null)) {
            throw new IllegalArgumentException("当前文档问答模式缺少有效的文档范围");
        }

        ConversationTraceRecorder.StageHandle rewriteStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(
                ConversationTraceStageCode.REWRITE,
                ExecutionMode.RETRIEVAL.name(),
                "正在生成检索友好的问题表达。",
                buildRewriteStageSnapshot(question, historySummary, null)
            );
        RagRewriteResult rewriteResult;
        try {
            rewriteResult = chatQueryRewriteService.rewrite(question, historySummary, traceRecorder);
            if (traceRecorder != null) {
                traceRecorder.completeStage(rewriteStage, "问题改写完成。", buildRewriteStageSnapshot(question, historySummary, rewriteResult));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(
                    rewriteStage,
                    "问题改写失败。",
                    exception.getMessage(),
                    buildRewriteStageSnapshot(question, historySummary, null)
                );
            }
            throw exception;
        }

        String rewriteQuestion = rewriteResult == null ? safeText(question) : firstNonBlank(rewriteResult.getRewrittenQuestion(), safeText(question));
        List<String> rewriteSubQuestions = rewriteResult == null || rewriteResult.getSubQuestions() == null || rewriteResult.getSubQuestions().isEmpty()
            ? List.of(rewriteQuestion)
            : rewriteResult.getSubQuestions();

        KnowledgeRoutePlan route = knowledgeRoutePlanner.plan(
            conversationId,
            taskInfo.exchangeId(),
            question,
            rewriteQuestion,
            rewriteSubQuestions,
            chatMode,
            knowledgeBaseSelection,
            selectedDocumentId,
            selectedDocumentName,
            selectedTaskId,
            traceRecorder
        );
        if (route.isClarificationRequired()) {
            return basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .mode(ExecutionMode.CLARIFICATION)
                .rewriteQuestion(rewriteQuestion)
                .rewriteSubQuestions(rewriteSubQuestions)
                .clarificationReply(route.getClarificationReply())
                .clarificationOptions(route.getClarificationOptions())
                .clarificationReason(route.getClarificationReason())
                .build();
        }
        Long recommendedDocumentId = route.getRecommendedDocumentId();
        List<Long> authorizedDocumentIds = route.getAuthorizedDocumentIds();
        List<Long> authorizedTaskIds = route.getAuthorizedTaskIds();

        ConversationTraceRecorder.StageHandle routeStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(
                ConversationTraceStageCode.ROUTE,
                ExecutionMode.RETRIEVAL.name(),
                "正在判定图查询还是混合检索。",
                RouteTraceSnapshot.of(Substage.RETRIEVAL_NAVIGATION)
            );
        DocumentNavigationDecision navigationDecision;
        try {
            navigationDecision = documentQuestionRouter.route(
                recommendedDocumentId,
                question,
                rewriteResult,
                historySummary,
                memoryContext == null ? "" : memoryContext.getAnswerRecentTranscript()
            );
            QueryUnderstandingResult queryUnderstanding = navigationDecision == null ? null : navigationDecision.getQueryUnderstanding();
            StructureNavigationResult structureNavigationResult = structureNavigationResolver.resolveAndAttach(
                navigationDecision,
                recommendedDocumentId
            );
            if (traceRecorder != null) {
                traceRecorder.completeStage(routeStage, "执行路由完成。", RouteTraceSnapshot.of(
                    Substage.RETRIEVAL_NAVIGATION,
                    Map.of(
                        "executionMode", navigationDecision == null || navigationDecision.getExecutionMode() == null ? "" : navigationDecision.getExecutionMode().name(),
                        "targetSectionHint", navigationDecision == null || navigationDecision.getStructureAnchor() == null ? "" : StrUtil.blankToDefault(navigationDecision.getStructureAnchor().getTargetSectionHint(), ""),
                        "targetItemIndex", navigationDecision == null || navigationDecision.getItemAnchor() == null || navigationDecision.getItemAnchor().getItemIndex() == null
                            ? ""
                            : String.valueOf(navigationDecision.getItemAnchor().getItemIndex()),
                        "retrievalIntent", navigationDecision == null || navigationDecision.getRetrievalIntent() == null
                            ? RetrievalIntent.GENERAL.name()
                            : navigationDecision.getRetrievalIntent().name(),
                        "queryUnderstanding", buildQueryUnderstandingTrace(queryUnderstanding),
                        "structureNavigation", buildStructureNavigationTrace(structureNavigationResult),
                        "navigationSummary", navigationDecision == null ? "" : StrUtil.blankToDefault(navigationDecision.getSummaryText(), "")
                    )
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(
                    routeStage,
                    "执行路由失败。",
                    exception.getMessage(),
                    RouteTraceSnapshot.of(Substage.RETRIEVAL_NAVIGATION)
                );
            }
            throw exception;
        }

        ExecutionMode executionMode = navigationDecision == null || navigationDecision.getExecutionMode() == null
            ? ExecutionMode.RETRIEVAL
            : navigationDecision.getExecutionMode();
        QueryUnderstandingResult queryUnderstanding = navigationDecision == null ? null : navigationDecision.getQueryUnderstanding();
        List<EvidenceAnchor> scopedEvidenceAnchors = conversationContextLoader.filterEvidenceAnchors(
            recentEvidenceAnchors,
            chatMode,
            recommendedDocumentId,
            knowledgeBaseSelection
        );
        conversationContextLoader.appendAnchorHints(historyPlanningContext, scopedEvidenceAnchors);
        AnswerHistoryContext routedAnswerHistoryContext = conversationContextLoader.buildAnswerHistoryContext(
            question,
            memoryContext == null ? "" : memoryContext.getAnswerRecentTranscript(),
            queryUnderstanding,
            scopedEvidenceAnchors
        );
        RetrievalPlan retrievalPlan = retrievalPlanAssembler.assemble(RetrievalPlanAssembler.AssemblyInput.builder()
            .chatMode(chatMode)
            .originalQuestion(question)
            .rewrittenQuestion(rewriteQuestion)
            .rewriteSubQuestions(rewriteSubQuestions)
            .historyPlanningContext(historyPlanningContext)
            .navigationDecision(navigationDecision)
            .queryUnderstanding(queryUnderstanding)
            .knowledgeBaseSelectionMode(knowledgeBoundaryResolver.selectionMode(knowledgeBaseSelection))
            .knowledgeBaseIds(knowledgeBoundaryResolver.selectedKnowledgeBaseIds(knowledgeBaseSelection))
            .allowedDocumentIds(knowledgeBoundaryResolver.allowedDocumentIds(knowledgeBaseSelection))
            .documentScope(authorizedDocumentIds)
            .taskScope(authorizedTaskIds)
            .knowledgeRoutePlan(route)
            .scopedEvidenceAnchors(scopedEvidenceAnchors)
            .runtimeOptions(knowledgeBoundaryResolver.runtimeOptions(knowledgeBaseSelection))
            .build());

        log.info("聊天编排完成: conversationId={}, chatMode={}, originalQuestion='{}', rewriteQuestion='{}', retrievalQuestion='{}', executionMode={}, retrievalIntent={}, targetSection='{}'",
            conversationId,
            chatMode,
            safeText(question),
            rewriteQuestion,
            retrievalPlan.normalizedQuery(),
            executionMode,
            retrievalPlan.getPrimaryIntent(),
            navigationDecision == null || navigationDecision.getStructureAnchor() == null ? "" : safeText(navigationDecision.getStructureAnchor().getTargetSectionHint()));

        return basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, routedAnswerHistoryContext, currentDate, currentDateText,
            requiresCurrentDateAnchoring, requiresFreshSearch)
            .mode(executionMode)
            .navigationDecision(navigationDecision)
            .queryUnderstanding(queryUnderstanding)
            .rewriteQuestion(rewriteQuestion)
            .rewriteSubQuestions(rewriteSubQuestions)
            .retrievalPlan(retrievalPlan)
            .noEvidenceReply(noEvidenceReplyPolicy.resolve(requiresFreshSearch, queryUnderstanding))
            .build();
    }

    private Map<String, Object> buildQueryUnderstandingTrace(QueryUnderstandingResult queryUnderstanding) {
        if (queryUnderstanding == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("queryType", queryUnderstanding.getQueryType() == null ? "" : queryUnderstanding.getQueryType().name());
        snapshot.put("channels", queryUnderstanding.getChannels() == null ? List.of() : queryUnderstanding.getChannels().stream().map(Enum::name).toList());
        snapshot.put("entities", queryUnderstanding.getEntities() == null ? List.of() : queryUnderstanding.getEntities());
        snapshot.put("targetEntities", queryUnderstanding.getTargetEntities() == null ? List.of() : queryUnderstanding.getTargetEntities());
        snapshot.put("excludedEntities", queryUnderstanding.getExcludedEntities() == null ? List.of() : queryUnderstanding.getExcludedEntities());
        snapshot.put("sectionAnchors", queryUnderstanding.getSectionAnchors() == null ? List.of() : queryUnderstanding.getSectionAnchors());
        snapshot.put("structureNavigationIntent", buildStructureNavigationIntentTrace(queryUnderstanding.getStructureNavigationIntent()));
        snapshot.put("tableOps", queryUnderstanding.getTableOps() == null ? List.of() : queryUnderstanding.getTableOps());
        snapshot.put("answerShapeRequirements", queryUnderstanding.getAnswerShapePlan() == null
            ? List.of()
            : queryUnderstanding.getAnswerShapePlan().requirements().stream().map(Enum::name).toList());
        snapshot.put("confidence", queryUnderstanding.getConfidence());
        snapshot.put("source", StrUtil.blankToDefault(queryUnderstanding.getSource(), ""));
        snapshot.put("reasons", queryUnderstanding.getReasons() == null ? List.of() : queryUnderstanding.getReasons());
        return snapshot;
    }

    private Map<String, Object> buildStructureNavigationIntentTrace(StructureNavigationIntent intent) {
        if (intent == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("operations", intent.getOperations() == null ? List.of() : intent.getOperations().stream().map(Enum::name).toList());
        snapshot.put("anchorStructureNodeId", intent.getAnchorStructureNodeId() == null ? "" : String.valueOf(intent.getAnchorStructureNodeId()));
        snapshot.put("anchorSectionPath", StrUtil.blankToDefault(intent.getAnchorSectionPath(), ""));
        snapshot.put("anchorCanonicalPath", StrUtil.blankToDefault(intent.getAnchorCanonicalPath(), ""));
        snapshot.put("sectionAnchors", intent.getSectionAnchors() == null ? List.of() : intent.getSectionAnchors());
        snapshot.put("confidence", intent.getConfidence());
        snapshot.put("source", StrUtil.blankToDefault(intent.getSource(), ""));
        return snapshot;
    }

    private Map<String, Object> buildStructureNavigationTrace(StructureNavigationResult result) {
        if (result == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", result.getDocumentId() == null ? "" : String.valueOf(result.getDocumentId()));
        snapshot.put("parseTaskId", result.getParseTaskId() == null ? "" : String.valueOf(result.getParseTaskId()));
        snapshot.put("anchorNodeId", result.getAnchorNodeId() == null ? "" : String.valueOf(result.getAnchorNodeId()));
        snapshot.put("current", buildStructureNodeTrace(result.getCurrent()));
        snapshot.put("parent", buildStructureNodeTrace(result.getParent()));
        snapshot.put("previous", buildStructureNodeTrace(result.getPreviousSibling()));
        snapshot.put("next", buildStructureNodeTrace(result.getNextSibling()));
        snapshot.put("directChildren", result.getDirectChildren() == null
            ? List.of()
            : result.getDirectChildren().stream().map(this::buildStructureNodeTrace).toList());
        snapshot.put("deterministic", result.isDeterministic());
        snapshot.put("missReason", StrUtil.blankToDefault(result.getMissReason(), ""));
        return snapshot;
    }

    private Map<String, Object> buildStructureNodeTrace(DocumentStructureNode node) {
        if (node == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("nodeId", node.getId() == null ? "" : String.valueOf(node.getId()));
        snapshot.put("parseTaskId", node.getParseTaskId() == null ? "" : String.valueOf(node.getParseTaskId()));
        snapshot.put("nodeNo", node.getNodeNo() == null ? "" : String.valueOf(node.getNodeNo()));
        snapshot.put("nodeType", node.getNodeType() == null ? "" : String.valueOf(node.getNodeType()));
        snapshot.put("depth", node.getDepth() == null ? "" : String.valueOf(node.getDepth()));
        snapshot.put("nodeCode", StrUtil.blankToDefault(node.getNodeCode(), ""));
        snapshot.put("title", StrUtil.blankToDefault(node.getTitle(), ""));
        snapshot.put("sectionPath", StrUtil.blankToDefault(node.getSectionPath(), ""));
        snapshot.put("canonicalPath", StrUtil.blankToDefault(node.getCanonicalPath(), ""));
        snapshot.put("parentNodeId", node.getParentNodeId() == null ? "" : String.valueOf(node.getParentNodeId()));
        snapshot.put("prevSiblingNodeId", node.getPrevSiblingNodeId() == null ? "" : String.valueOf(node.getPrevSiblingNodeId()));
        snapshot.put("nextSiblingNodeId", node.getNextSiblingNodeId() == null ? "" : String.valueOf(node.getNextSiblingNodeId()));
        snapshot.put("syntaxSchemaVersion", StrUtil.blankToDefault(node.getSyntaxSchemaVersion(), ""));
        snapshot.put("syntaxSourceSha256", StrUtil.blankToDefault(node.getSyntaxSourceSha256(), ""));
        snapshot.put("syntaxNodeId", StrUtil.blankToDefault(node.getSyntaxNodeId(), ""));
        snapshot.put("syntaxNodeType", StrUtil.blankToDefault(node.getSyntaxNodeType(), ""));
        snapshot.put("syntaxSourceOrigin", StrUtil.blankToDefault(node.getSyntaxSourceOrigin(), ""));
        snapshot.put("sourceSpan", Map.of(
            "startByte", node.getSourceStartByte() == null ? "" : String.valueOf(node.getSourceStartByte()),
            "endByte", node.getSourceEndByte() == null ? "" : String.valueOf(node.getSourceEndByte()),
            "startLine", node.getSourceStartLine() == null ? "" : String.valueOf(node.getSourceStartLine()),
            "startColumn", node.getSourceStartColumn() == null ? "" : String.valueOf(node.getSourceStartColumn()),
            "endLine", node.getSourceEndLine() == null ? "" : String.valueOf(node.getSourceEndLine()),
            "endColumn", node.getSourceEndColumn() == null ? "" : String.valueOf(node.getSourceEndColumn())
        ));
        return snapshot;
    }

    private ConversationExecutionPlan.ConversationExecutionPlanBuilder basePlan(String question,
                                                                                ChatQueryMode chatMode,
                                                                                ConversationMemoryContext memoryContext,
                                                                                HistoryPlanningContext historyPlanningContext,
                                                                                String historySummary,
                                                                                AnswerHistoryContext answerHistoryContext,
                                                                                LocalDate currentDate,
                                                                                String currentDateText,
                                                                                boolean requiresCurrentDateAnchoring,
                                                                                boolean requiresFreshSearch) {
        return ConversationExecutionPlan.builder()
            .chatMode(chatMode)
            .originalQuestion(question)
            .agentQuestion(question)
            .rewriteQuestion(question)
            .rewriteSubQuestions(List.of(question))
            .historySummary(historySummary)
            .longTermSummary(memoryContext.getLongTermSummary())
            .historyPlanningContext(historyPlanningContext)
            .recentHistoryTranscript(memoryContext.getRecentTranscript())
            .answerRecentTranscript(memoryContext.getAnswerRecentTranscript())
            .answerHistoryContext(answerHistoryContext)
            .historyCompressionApplied(memoryContext.isCompressionApplied())
            .historyCoveredExchangeId(memoryContext.getCoveredExchangeId())
            .historyCoveredExchangeCount(memoryContext.getCoveredExchangeCount())
            .historyCompressionCount(memoryContext.getCompressionCount())
            .currentDate(currentDate)
            .currentDateText(currentDateText)
            .requiresCurrentDateAnchoring(requiresCurrentDateAnchoring)
            .requiresFreshSearch(requiresFreshSearch)
            .noEvidenceReply(noEvidenceReplyPolicy.defaultReply());
    }

    private Map<String, Object> buildRewriteStageSnapshot(String question,
                                                          String historySummary,
                                                          RagRewriteResult rewriteResult) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("originalQuestion", StrUtil.blankToDefault(question, ""));
        snapshot.put("historyContext", StrUtil.blankToDefault(historySummary, ""));
        snapshot.put("rewriteQuestion", rewriteResult == null ? "" : StrUtil.blankToDefault(rewriteResult.getRewrittenQuestion(), ""));
        snapshot.put("subQuestions", rewriteResult == null || rewriteResult.getSubQuestions() == null ? List.of() : rewriteResult.getSubQuestions());
        snapshot.put("rawModelOutput", rewriteResult == null ? "" : StrUtil.blankToDefault(rewriteResult.getRawModelOutput(), ""));

        ChatRagProperties.RewriteOptionsProperties rewriteOptions = properties == null ? null : properties.getRewriteOptions();
        boolean overrideEnabled = rewriteOptions != null && rewriteOptions.isEnabled();
        snapshot.put("rewriteOverrideEnabled", overrideEnabled);
        snapshot.put("rewriteTemperature", rewriteOptions == null ? null : rewriteOptions.getTemperature());
        snapshot.put("rewriteTopP", rewriteOptions == null ? null : rewriteOptions.getTopP());
        snapshot.put("rewriteThinking", rewriteOptions == null ? null : rewriteOptions.getThinking());
        return snapshot;
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private String firstNonBlank(String left, String right) {
        if (StrUtil.isNotBlank(left)) {
            return left.trim();
        }
        return safeText(right);
    }

}
