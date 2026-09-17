package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.smartledge.ai.chatagent.model.trace.RouteTraceSnapshot;
import org.smartledge.ai.chatagent.model.trace.RouteTraceSnapshot.Substage;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.chatagent.rag.model.RouteScopeAuthorizationMode;
import org.smartledge.ai.chatagent.service.ConversationTraceRecorder;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.rag.runtime.model.route.DocumentRouteCandidate;
import org.smartledge.ai.rag.runtime.model.route.KnowledgeRouteContext;
import org.smartledge.ai.rag.runtime.model.route.KnowledgeRouteDecision;
import org.smartledge.ai.rag.runtime.port.KnowledgeRoutePort;
import org.smartledge.enums.ChatQueryMode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @description: 自动知识问答与当前文档问答的知识路由编排
 * @author: Song
 **/

@Slf4j
@Service
public class KnowledgeRoutePlanner {

    private final KnowledgeRoutePort knowledgeRouteService;
    private final ChatRagProperties properties;
    private final KnowledgeBoundaryResolver knowledgeBoundaryResolver;

    public KnowledgeRoutePlanner(KnowledgeRoutePort knowledgeRouteService,
                                 ChatRagProperties properties,
                                 KnowledgeBoundaryResolver knowledgeBoundaryResolver) {
        this.knowledgeRouteService = knowledgeRouteService;
        this.properties = properties;
        this.knowledgeBoundaryResolver = knowledgeBoundaryResolver;
    }

    public KnowledgeRoutePlan plan(String conversationId,
                                   long exchangeId,
                                   String question,
                                   String rewriteQuestion,
                                   List<String> rewriteSubQuestions,
                                   ChatQueryMode chatMode,
                                   KnowledgeBaseSelectionSnapshot knowledgeBaseSelection,
                                   Long selectedDocumentId,
                                   String selectedDocumentName,
                                   Long selectedTaskId,
                                   ConversationTraceRecorder traceRecorder) {
        AllowedExecutionScope allowedScope = resolveAllowedExecutionScope(knowledgeBaseSelection);
        if (chatMode == ChatQueryMode.DOCUMENT) {
            if (!allowedScope.consistent() || !allowedScope.contains(selectedDocumentId, selectedTaskId)) {
                throw new IllegalArgumentException("Selected document/task is outside the current allowed knowledge scope");
            }
            knowledgeRouteService.recordShadowRoute(conversationId, exchangeId, selectedDocumentId,
                buildRouteContext(question, rewriteQuestion, knowledgeBaseSelection));
            return KnowledgeRoutePlan.builder()
                .authorizationMode(RouteScopeAuthorizationMode.EXPLICIT_DOCUMENT)
                .scopeAuthorizationReason("User-selected document/task pair authorizes the execution scope")
                .clarificationRequired(false)
                .recommendedDocumentId(selectedDocumentId)
                .recommendedDocumentName(selectedDocumentName)
                .recommendedTaskId(selectedTaskId)
                .authorizedDocumentIds(List.of(selectedDocumentId))
                .authorizedTaskIds(List.of(selectedTaskId))
                .build();
        }
        if (chatMode != ChatQueryMode.AUTO_DOCUMENT) {
            throw new IllegalArgumentException("Knowledge route planning requires DOCUMENT or AUTO_DOCUMENT mode");
        }

        KnowledgeRouteContext routeContext = buildRouteContext(question, rewriteQuestion, knowledgeBaseSelection);
        KnowledgeRouteDecision routeDecision = routeAdvisory(conversationId, routeContext);
        List<DocumentRouteCandidate> inScopeCandidates = selectAutoCandidates(routeDecision, allowedScope);
        DocumentRouteCandidate recommendation = selectRecommendation(routeDecision, inScopeCandidates);
        RouteScopeAuthorization authorization = allowedScope.executable()
            ? RouteScopeAuthorization.knowledgeBaseScope(allowedScope, recommendation)
            : RouteScopeAuthorization.clarification(allowedScope.reason());

        // AUTO_DOCUMENT never hard-selects a route candidate; the full ready KB scope remains authoritative.
        knowledgeRouteService.recordAutoRoute(conversationId, exchangeId, routeContext, routeDecision, null);
        recordAutoDocumentRouteTrace(traceRecorder, routeDecision, inScopeCandidates, allowedScope, authorization);

        if (authorization.clarificationRequired()) {
            return KnowledgeRoutePlan.builder()
                .routeDecision(routeDecision)
                .authorizationMode(authorization.mode())
                .scopeAuthorizationReason(authorization.reason())
                .clarificationRequired(true)
                .clarificationReply("当前选择的知识范围没有可检索的已就绪文档，请重新选择知识库或等待文档完成索引。")
                .clarificationOptions(List.of())
                .clarificationReason(authorization.reason())
                .authorizedDocumentIds(List.of())
                .authorizedTaskIds(List.of())
                .build();
        }

        return KnowledgeRoutePlan.builder()
            .routeDecision(routeDecision)
            .authorizationMode(authorization.mode())
            .scopeAuthorizationReason(authorization.reason())
            .clarificationRequired(false)
            .recommendedDocumentId(authorization.recommendedDocumentId())
            .recommendedDocumentName(authorization.recommendedDocumentName())
            .recommendedTaskId(authorization.recommendedTaskId())
            .authorizedDocumentIds(authorization.authorizedDocumentIds())
            .authorizedTaskIds(authorization.authorizedTaskIds())
            .build();
    }

    private void recordAutoDocumentRouteTrace(ConversationTraceRecorder traceRecorder,
                                              KnowledgeRouteDecision routeDecision,
                                              List<DocumentRouteCandidate> candidateDocuments,
                                              AllowedExecutionScope allowedScope,
                                              RouteScopeAuthorization authorization) {
        if (traceRecorder == null) {
            return;
        }
        Map<String, Object> snapshot = buildAutoDocumentRouteSnapshot(
            routeDecision,
            candidateDocuments,
            allowedScope,
            authorization
        );
        ConversationTraceRecorder.StageHandle autoRouteStage = traceRecorder.startStage(
            ConversationTraceStageCode.ROUTE,
            "AUTO_DOCUMENT",
            "正在执行知识范围、主题、候选文档路由。",
            snapshot
        );
        traceRecorder.completeStage(
            autoRouteStage,
            authorization.clarificationRequired()
                ? "当前知识范围没有可执行的已就绪文档，已转入澄清。"
                : "知识范围路由完成。",
            snapshot
        );
    }

    private Map<String, Object> buildAutoDocumentRouteSnapshot(KnowledgeRouteDecision routeDecision,
                                                               List<DocumentRouteCandidate> candidateDocuments,
                                                               AllowedExecutionScope allowedScope,
                                                               RouteScopeAuthorization authorization) {
        DocumentRouteCandidate recommendation = authorization.recommendation();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("routeStatus", routeDecision == null ? "" : StrUtil.blankToDefault(routeDecision.getRouteStatus(), ""));
        snapshot.put("routeSource", routeDecision == null ? "" : StrUtil.blankToDefault(routeDecision.getSource(), ""));
        snapshot.put("confidence", routeDecision == null || routeDecision.getConfidence() == null ? "" : routeDecision.getConfidence().toPlainString());
        snapshot.put("reason", routeDecision == null ? "" : StrUtil.blankToDefault(routeDecision.getReason(), ""));
        snapshot.put("degraded", routeDecision != null && routeDecision.isDegraded());
        snapshot.put("degradedReasons", routeDecision == null || routeDecision.getDegradedReasons() == null ? List.of() : routeDecision.getDegradedReasons());
        snapshot.put("clarificationRequired", authorization.clarificationRequired());
        snapshot.put("authorizationMode", authorization.mode().name());
        snapshot.put("scopeAuthorizationReason", authorization.reason());
        snapshot.put("scopeAuthority", authorization.mode() == RouteScopeAuthorizationMode.KNOWLEDGE_BASE_ALLOWED_SCOPE
            ? "KNOWLEDGE_BASE_SELECTION_SNAPSHOT"
            : "NO_EXECUTABLE_READY_SCOPE");
        snapshot.put("allowedDocumentIds", allowedScope == null ? List.of() : allowedScope.documentIds());
        snapshot.put("allowedTaskIds", allowedScope == null ? List.of() : allowedScope.taskIds());
        snapshot.put("authorizedDocumentIds", authorization.authorizedDocumentIds());
        snapshot.put("authorizedTaskIds", authorization.authorizedTaskIds());
        snapshot.put("recommendationThreshold", recommendationThreshold());
        snapshot.put("recommendationDocumentId", recommendation == null ? "" : StrUtil.blankToDefault(recommendation.getDocumentId(), ""));
        snapshot.put("recommendationDocumentName", recommendation == null ? "" : StrUtil.blankToDefault(recommendation.getDocumentName(), ""));
        snapshot.put("recommendationTaskId", recommendation == null ? "" : StrUtil.blankToDefault(recommendation.getLastIndexTaskId(), ""));
        snapshot.put("scopeCandidates", buildScopeRouteTrace(routeDecision));
        snapshot.put("topicCandidates", buildTopicRouteTrace(routeDecision));
        List<DocumentRouteCandidate> rawCandidates = routeDecision == null || routeDecision.getDocuments() == null
            ? List.of()
            : routeDecision.getDocuments();
        snapshot.put("candidateDocuments", buildDocumentRouteTrace(rawCandidates));
        snapshot.put("candidateDocumentCount", rawCandidates.size());
        snapshot.put("inScopeRecommendationCandidates", buildDocumentRouteTrace(candidateDocuments));
        return RouteTraceSnapshot.of(Substage.AUTO_DOCUMENT_SCOPE, snapshot);
    }

    private List<Map<String, Object>> buildScopeRouteTrace(KnowledgeRouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.getScopes() == null) {
            return List.of();
        }
        return routeDecision.getScopes().stream()
            .limit(5)
            .map(scope -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("scopeId", scope.getScopeId() == null ? "" : String.valueOf(scope.getScopeId()));
                item.put("scopeName", StrUtil.blankToDefault(scope.getScopeName(), ""));
                item.put("score", scope.getScore() == null ? "" : scope.getScore().toPlainString());
                item.put("reason", StrUtil.blankToDefault(scope.getReason(), ""));
                item.put("source", StrUtil.blankToDefault(scope.getSource(), ""));
                item.put("features", scope.getFeatures() == null ? Map.of() : scope.getFeatures());
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> buildTopicRouteTrace(KnowledgeRouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.getTopics() == null) {
            return List.of();
        }
        return routeDecision.getTopics().stream()
            .limit(5)
            .map(topic -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("scopeId", topic.getScopeId() == null ? "" : String.valueOf(topic.getScopeId()));
                item.put("topicId", topic.getTopicId() == null ? "" : String.valueOf(topic.getTopicId()));
                item.put("topicName", StrUtil.blankToDefault(topic.getTopicName(), ""));
                item.put("score", topic.getScore() == null ? "" : topic.getScore().toPlainString());
                item.put("reason", StrUtil.blankToDefault(topic.getReason(), ""));
                item.put("source", StrUtil.blankToDefault(topic.getSource(), ""));
                item.put("features", topic.getFeatures() == null ? Map.of() : topic.getFeatures());
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> buildDocumentRouteTrace(List<DocumentRouteCandidate> candidateDocuments) {
        if (candidateDocuments == null || candidateDocuments.isEmpty()) {
            return List.of();
        }
        return candidateDocuments.stream()
            .limit(8)
            .map(document -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("documentId", StrUtil.blankToDefault(document.getDocumentId(), ""));
                item.put("documentName", StrUtil.blankToDefault(document.getDocumentName(), ""));
                item.put("lastIndexTaskId", StrUtil.blankToDefault(document.getLastIndexTaskId(), ""));
                item.put("score", document.getScore() == null ? "" : document.getScore().toPlainString());
                item.put("reason", StrUtil.blankToDefault(document.getReason(), ""));
                item.put("source", StrUtil.blankToDefault(document.getSource(), ""));
                item.put("features", document.getFeatures() == null ? Map.of() : document.getFeatures());
                return item;
            })
            .toList();
    }

    private List<DocumentRouteCandidate> selectAutoCandidates(KnowledgeRouteDecision routeDecision,
                                                              AllowedExecutionScope allowedScope) {
        if (routeDecision == null || routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty()) {
            return List.of();
        }
        if (allowedScope == null || !allowedScope.executable()) {
            return List.of();
        }
        return routeDecision.getDocuments().stream()
            .filter(Objects::nonNull)
            .filter(item -> candidateMatchesAllowedScope(item, allowedScope))
            .limit(5)
            .toList();
    }

    private boolean candidateMatchesAllowedScope(DocumentRouteCandidate candidate,
                                                  AllowedExecutionScope allowedScope) {
        Long documentId = parsePositiveId(candidate.getDocumentId());
        Long taskId = parsePositiveId(candidate.getLastIndexTaskId());
        return allowedScope.contains(documentId, taskId);
    }

    private KnowledgeRouteContext buildRouteContext(String question,
                                                    String rewriteQuestion,
                                                    KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return KnowledgeRouteContext.builder()
            .question(question)
            .rewriteQuestion(rewriteQuestion)
            .knowledgeBaseSelectionMode(knowledgeBoundaryResolver.selectionMode(knowledgeBaseSelection))
            .selectedKnowledgeBaseIds(knowledgeBoundaryResolver.selectedKnowledgeBaseIds(knowledgeBaseSelection))
            .selectedKnowledgeBaseNames(knowledgeBoundaryResolver.selectedKnowledgeBaseNames(knowledgeBaseSelection))
            .allowedDocuments(knowledgeBoundaryResolver.allowedDocuments(knowledgeBaseSelection))
            .allowedDocumentIds(knowledgeBoundaryResolver.allowedDocumentIds(knowledgeBaseSelection))
            .build();
    }

    private KnowledgeRouteDecision routeAdvisory(String conversationId,
                                                 KnowledgeRouteContext routeContext) {
        try {
            KnowledgeRouteDecision decision = knowledgeRouteService.route(routeContext);
            return decision == null ? unavailableRouteDecision("ROUTE_ADVISOR_NULL_RESULT") : decision;
        }
        catch (RuntimeException exception) {
            log.warn("知识路由建议不可用，保留已选知识范围内的普通召回: conversationId={}", conversationId, exception);
            return unavailableRouteDecision("ROUTE_ADVISOR_FAILURE");
        }
    }

    private KnowledgeRouteDecision unavailableRouteDecision(String reason) {
        KnowledgeRouteDecision decision = new KnowledgeRouteDecision();
        decision.setRouteStatus("FAILED");
        decision.setConfidence(BigDecimal.ZERO);
        decision.setSource("NONE");
        decision.setDegraded(true);
        decision.setDegradedReasons(List.of(reason));
        decision.setReason("Route advice is unavailable; ordinary retrieval keeps the explicit knowledge scope");
        return decision;
    }

    private DocumentRouteCandidate selectRecommendation(KnowledgeRouteDecision routeDecision,
                                                        List<DocumentRouteCandidate> inScopeCandidates) {
        if (routeDecision == null || routeDecision.getConfidence() == null
            || !"SUCCESS".equals(routeDecision.getRouteStatus())
            || inScopeCandidates == null || inScopeCandidates.isEmpty()) {
            return null;
        }
        double confidence = routeDecision.getConfidence().doubleValue();
        DocumentRouteCandidate candidate = inScopeCandidates.get(0);
        if (!Double.isFinite(confidence) || confidence < recommendationThreshold() || confidence > 1D
            || !isOriginalTopCandidate(routeDecision, candidate) || !isPositiveCandidate(candidate)) {
            return null;
        }
        return candidate;
    }

    private boolean isPositiveCandidate(DocumentRouteCandidate candidate) {
        return candidate != null && candidate.getScore() != null && candidate.getScore().signum() > 0;
    }

    private boolean isOriginalTopCandidate(KnowledgeRouteDecision routeDecision,
                                           DocumentRouteCandidate candidate) {
        if (routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty() || candidate == null) {
            return false;
        }
        DocumentRouteCandidate originalTop = routeDecision.getDocuments().get(0);
        return originalTop != null
            && Objects.equals(parsePositiveId(originalTop.getDocumentId()), parsePositiveId(candidate.getDocumentId()))
            && Objects.equals(parsePositiveId(originalTop.getLastIndexTaskId()), parsePositiveId(candidate.getLastIndexTaskId()));
    }

    private Long parsePositiveId(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0L ? parsed : null;
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double recommendationThreshold() {
        ChatRagProperties.AutoRouteProperties autoRoute = properties == null ? null : properties.getAutoRoute();
        return autoRoute == null ? 0.55D : clampThreshold(autoRoute.getRecommendationThreshold(), 0.55D);
    }

    private double clampThreshold(double threshold, double fallback) {
        if (Double.isNaN(threshold) || Double.isInfinite(threshold)) {
            return fallback;
        }
        return Math.max(0D, Math.min(1D, threshold));
    }

    private AllowedExecutionScope resolveAllowedExecutionScope(KnowledgeBaseSelectionSnapshot selection) {
        Map<Long, Long> tasksByDocument = new LinkedHashMap<>();
        boolean descriptorConflict = false;
        for (KnowledgeDocumentDescriptor descriptor : knowledgeBoundaryResolver.allowedDocuments(selection)) {
            if (descriptor == null || descriptor.getDocumentId() == null || descriptor.getDocumentId() <= 0L
                || descriptor.getLastIndexTaskId() == null || descriptor.getLastIndexTaskId() <= 0L) {
                descriptorConflict = true;
                continue;
            }
            Long previousTask = tasksByDocument.putIfAbsent(descriptor.getDocumentId(), descriptor.getLastIndexTaskId());
            if (previousTask != null && !Objects.equals(previousTask, descriptor.getLastIndexTaskId())) {
                descriptorConflict = true;
            }
        }
        List<Long> documentIds = List.copyOf(tasksByDocument.keySet());
        List<Long> taskIds = List.copyOf(tasksByDocument.values());
        List<Long> declaredDocumentIds = normalizePositiveIds(knowledgeBoundaryResolver.allowedDocumentIds(selection));
        List<Long> declaredTaskIds = normalizePositiveIds(knowledgeBoundaryResolver.allowedTaskIds(selection));
        if (descriptorConflict || !Objects.equals(documentIds, declaredDocumentIds) || !Objects.equals(taskIds, declaredTaskIds)) {
            return AllowedExecutionScope.inconsistent("Knowledge selection snapshot document/task identities are inconsistent");
        }
        if (documentIds.isEmpty()) {
            return AllowedExecutionScope.empty("The selected knowledge scope has no ready document/task pair");
        }
        return AllowedExecutionScope.ready(documentIds, taskIds);
    }

    private List<Long> normalizePositiveIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream()
            .filter(Objects::nonNull)
            .filter(id -> id > 0L)
            .distinct()
            .toList();
    }

    private record RouteScopeAuthorization(
        RouteScopeAuthorizationMode mode,
        List<Long> authorizedDocumentIds,
        List<Long> authorizedTaskIds,
        DocumentRouteCandidate recommendation,
        String reason
    ) {

        private RouteScopeAuthorization {
            mode = Objects.requireNonNull(mode, "Route authorization mode is required");
            authorizedDocumentIds = authorizedDocumentIds == null ? List.of() : List.copyOf(authorizedDocumentIds);
            authorizedTaskIds = authorizedTaskIds == null ? List.of() : List.copyOf(authorizedTaskIds);
            reason = reason == null ? "" : reason.trim();
            if (authorizedDocumentIds.size() != authorizedTaskIds.size()) {
                throw new IllegalArgumentException("Authorized document/task scope must stay aligned");
            }
            if (reason.isEmpty()) {
                throw new IllegalArgumentException("Route scope authorization reason is required");
            }
        }

        private static RouteScopeAuthorization clarification(String reason) {
            return new RouteScopeAuthorization(
                RouteScopeAuthorizationMode.CLARIFICATION_REQUIRED, List.of(), List.of(), null, reason);
        }

        private static RouteScopeAuthorization knowledgeBaseScope(AllowedExecutionScope scope,
                                                                  DocumentRouteCandidate recommendation) {
            return new RouteScopeAuthorization(
                RouteScopeAuthorizationMode.KNOWLEDGE_BASE_ALLOWED_SCOPE,
                scope.documentIds(),
                scope.taskIds(),
                recommendation,
                "Knowledge-base selection snapshot authorizes the complete ready scope"
            );
        }

        private boolean clarificationRequired() {
            return mode == RouteScopeAuthorizationMode.CLARIFICATION_REQUIRED;
        }

        private Long recommendedDocumentId() {
            return recommendation == null ? null : positiveId(recommendation.getDocumentId());
        }

        private String recommendedDocumentName() {
            return recommendation == null ? "" : StrUtil.blankToDefault(recommendation.getDocumentName(), "");
        }

        private Long recommendedTaskId() {
            return recommendation == null ? null : positiveId(recommendation.getLastIndexTaskId());
        }

        private static Long positiveId(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                long parsed = Long.parseLong(value.trim());
                return parsed > 0L ? parsed : null;
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private record AllowedExecutionScope(
        List<Long> documentIds,
        List<Long> taskIds,
        boolean consistent,
        String reason
    ) {

        private AllowedExecutionScope {
            documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
            taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
            reason = reason == null ? "" : reason;
        }

        private static AllowedExecutionScope ready(List<Long> documentIds, List<Long> taskIds) {
            return new AllowedExecutionScope(documentIds, taskIds, true, "");
        }

        private static AllowedExecutionScope empty(String reason) {
            return new AllowedExecutionScope(List.of(), List.of(), true, reason);
        }

        private static AllowedExecutionScope inconsistent(String reason) {
            return new AllowedExecutionScope(List.of(), List.of(), false, reason);
        }

        private boolean executable() {
            return consistent && !documentIds.isEmpty() && documentIds.size() == taskIds.size();
        }

        private boolean contains(Long documentId, Long taskId) {
            if (documentId == null || taskId == null) {
                return false;
            }
            int index = documentIds.indexOf(documentId);
            return index >= 0 && index < taskIds.size() && Objects.equals(taskIds.get(index), taskId);
        }
    }
}
