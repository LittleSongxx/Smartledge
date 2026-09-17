package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentProfile;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeScopeNode;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeTopicNode;
import org.smartledge.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeRouteTraceMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeScopeNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.smartledge.ai.manage.model.route.DocumentRouteCandidate;
import org.smartledge.ai.manage.model.route.KnowledgeRouteContext;
import org.smartledge.ai.manage.model.route.KnowledgeRouteDecision;
import org.smartledge.ai.manage.model.route.ScopeRouteCandidate;
import org.smartledge.ai.manage.model.route.TopicRouteCandidate;
import org.smartledge.ai.manage.service.KnowledgeRouteIndexService;
import org.smartledge.ai.manage.service.KnowledgeRouteService;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentIndexStatusEnum;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @description: 服务实现层
 * @author: Song
 **/
@Slf4j
@AllArgsConstructor
@Service
public class KnowledgeRouteServiceImpl implements KnowledgeRouteService {

    private static final int ROUTE_STATUS_SUCCESS = 1;
    private static final int ROUTE_STATUS_LOW_CONFIDENCE = 2;
    private static final int ROUTE_STATUS_FAILED = 3;
    private static final int ROUTE_EMBEDDING_BATCH_SIZE = 10;
    private static final int DOCUMENT_CANDIDATE_LIMIT = 5;
    private static final BigDecimal LOW_CONFIDENCE_THRESHOLD = BigDecimal.valueOf(0.55D);

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentDocumentProfileMapper documentProfileMapper;
    private final SuperAgentKnowledgeScopeNodeMapper scopeNodeMapper;
    private final SuperAgentKnowledgeTopicNodeMapper topicNodeMapper;
    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;
    private final SuperAgentKnowledgeRouteTraceMapper knowledgeRouteTraceMapper;
    private final ObjectProvider<EmbeddingPort> embeddingModelProvider;
    private final ObjectProvider<KnowledgeRouteIndexService> knowledgeRouteIndexServiceProvider;
    private final UidGenerator uidGenerator;

    @Override
    public KnowledgeRouteDecision route(KnowledgeRouteContext context) {
        RouteQueryContext queryContext = buildQueryContext(context);
        KnowledgeRouteDecision decision = new KnowledgeRouteDecision();
        if (StrUtil.isBlank(queryContext.routingText())) {
            decision.setRouteStatus("FAILED");
            decision.setReason("问题为空，无法执行知识路由");
            return decision;
        }
        List<ScopeRouteCandidate> scopeCandidates = rankScopes(queryContext);
        List<TopicRouteCandidate> topicCandidates = rankTopics(queryContext, scopeCandidates);
        DocumentRanking documentRanking = rankDocuments(queryContext, scopeCandidates, topicCandidates);
        List<DocumentRouteCandidate> documentCandidates = documentRanking.candidates();
        decision.setScopes(scopeCandidates);
        decision.setTopics(topicCandidates);
        decision.setDocuments(documentCandidates);
        decision.setSource(resolveDecisionSource(documentCandidates));
        decision.setDegraded(queryContext.diagnostics().isDegraded());
        decision.setDegradedReasons(queryContext.diagnostics().degradedReasons());
        BigDecimal confidence = documentRanking.confidence();
        decision.setConfidence(confidence);
        if (documentCandidates.isEmpty()) {
            decision.setRouteStatus("FAILED");
        }
        else if (confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0) {
            decision.setRouteStatus("LOW_CONFIDENCE");
        }
        else {
            decision.setRouteStatus("SUCCESS");
        }
        decision.setReason(documentCandidates.isEmpty()
            ? "没有找到可用候选文档"
            : resolveDecisionReason(documentCandidates, confidence));
        log.info("知识范围路由完成: question='{}', rewriteQuestion='{}', scopeCount={}, topicCount={}, documentCount={}, confidence={}, source={}, degraded={}, topDocument='{}'",
            StrUtil.blankToDefault(queryContext.originalQuestion(), ""),
            StrUtil.blankToDefault(queryContext.rewriteQuestion(), ""),
            scopeCandidates.size(),
            topicCandidates.size(),
            documentCandidates.size(),
            confidence,
            decision.getSource(),
            decision.isDegraded(),
            documentCandidates.isEmpty() ? "" : documentCandidates.get(0).getDocumentName());
        return decision;
    }

    @Override
    public void recordShadowRoute(String conversationId,
                                  long exchangeId,
                                  Long selectedDocumentId,
                                  KnowledgeRouteContext context) {
        try {
            KnowledgeRouteDecision decision = route(context);
            saveTrace(conversationId, exchangeId, selectedDocumentId, context, "shadow", decision);
        }
        catch (Exception exception) {
            log.warn("记录知识路由影子结果失败: conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }

    @Override
    public void recordAutoRoute(String conversationId,
                                long exchangeId,
                                KnowledgeRouteContext context,
                                KnowledgeRouteDecision decision,
                                Long hardScopedDocumentId) {
        try {
            saveTrace(conversationId, exchangeId, hardScopedDocumentId, context, "auto", decision);
        }
        catch (Exception exception) {
            log.warn("记录知识路由 AUTO 结果失败: conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }

    private void saveTrace(String conversationId,
                           long exchangeId,
                           Long selectedDocumentId,
                           KnowledgeRouteContext context,
                           String mode,
                           KnowledgeRouteDecision decision) {
        SuperAgentKnowledgeRouteTrace trace = new SuperAgentKnowledgeRouteTrace();
        trace.setId(uidGenerator.getUid());
        Long tenantId = org.smartledge.database.tenant.TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalStateException("写入知识路由追踪缺少租户上下文");
        }
        trace.setTenantId(tenantId);
        trace.setConversationId(conversationId);
        trace.setExchangeId(exchangeId);
        trace.setQuestion(context == null ? "" : context.getQuestion());
        trace.setRewriteQuestion(context == null ? "" : context.getRewriteQuestion());
        trace.setMode(mode);
        trace.setKnowledgeBaseSelectionMode(context == null || context.getKnowledgeBaseSelectionMode() == null ? KnowledgeBaseSelectionMode.NONE.name() : context.getKnowledgeBaseSelectionMode().name());
        trace.setSelectedKnowledgeBaseIdsJson(writeStringJson(context == null ? List.of() : context.getSelectedKnowledgeBaseIds().stream().map(String::valueOf).toList()));
        trace.setSelectedKnowledgeBaseNamesJson(writeStringJson(context == null ? List.of() : context.getSelectedKnowledgeBaseNames()));
        trace.setAllowedDocumentIdsJson(writeStringJson(context == null ? List.of() : context.getAllowedDocumentIds().stream().map(String::valueOf).toList()));
        trace.setTopScopesJson(writeScopeJson(decision == null ? List.of() : decision.getScopes()));
        trace.setTopTopicsJson(writeTopicJson(decision == null ? List.of() : decision.getTopics()));
        trace.setTopDocumentsJson(writeDocumentJson(decision == null ? List.of() : decision.getDocuments()));
        trace.setSelectedDocumentId(selectedDocumentId);
        trace.setHitSelectedDocument(resolveHitSelectedDocument(selectedDocumentId, decision));
        trace.setConfidence(decision == null ? BigDecimal.ZERO : decision.getConfidence());
        trace.setRouteStatus(resolveRouteStatus(decision));
        trace.setErrorMsg(decision == null ? "" : StrUtil.blankToDefault(decision.getReason(), ""));
        trace.setStatus(BusinessStatus.YES.getCode());
        knowledgeRouteTraceMapper.insert(trace);
    }

    private Integer resolveRouteStatus(KnowledgeRouteDecision decision) {
        if (decision == null) {
            return ROUTE_STATUS_FAILED;
        }
        return switch (StrUtil.blankToDefault(decision.getRouteStatus(), "FAILED")) {
            case "SUCCESS" -> ROUTE_STATUS_SUCCESS;
            case "LOW_CONFIDENCE" -> ROUTE_STATUS_LOW_CONFIDENCE;
            default -> ROUTE_STATUS_FAILED;
        };
    }

    private Integer resolveHitSelectedDocument(Long selectedDocumentId, KnowledgeRouteDecision decision) {
        if (selectedDocumentId == null || decision == null || decision.getDocuments() == null || decision.getDocuments().isEmpty()) {
            return null;
        }
        boolean hit = decision.getDocuments().stream()
            .limit(3)
            .anyMatch(item -> Objects.equals(String.valueOf(selectedDocumentId), item.getDocumentId()));
        return hit ? 1 : 0;
    }

    private List<ScopeRouteCandidate> rankScopes(RouteQueryContext queryContext) {
        LambdaQueryWrapper<SuperAgentKnowledgeScopeNode> wrapper = new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode());
        if (queryContext.selectedKnowledgeBaseIds() != null && !queryContext.selectedKnowledgeBaseIds().isEmpty()) {
            wrapper.in(SuperAgentKnowledgeScopeNode::getKnowledgeBaseId, queryContext.selectedKnowledgeBaseIds());
        }
        List<SuperAgentKnowledgeScopeNode> nodes = scopeNodeMapper.selectList(wrapper);
        if (nodes.isEmpty()) {
            return List.of();
        }
        List<String> routeTexts = nodes.stream()
            .map(node -> join(node.getScopeName(), node.getDescription(), node.getAliases(), node.getExamples()))
            .toList();
        List<Double> semanticScores = computeSemanticScores(queryContext, routeTexts);
        Map<Long, Double> lexicalScores = searchRouteIndex(queryContext, "scope", 5).hits().stream()
            .filter(hit -> hit.entityId() != null)
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::entityId, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        return buildScopeCandidates(nodes, semanticScores, lexicalScores);
    }

    private List<TopicRouteCandidate> rankTopics(RouteQueryContext queryContext, List<ScopeRouteCandidate> scopeCandidates) {
        LambdaQueryWrapper<SuperAgentKnowledgeTopicNode> wrapper = new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode());
        if (queryContext.selectedKnowledgeBaseIds() != null && !queryContext.selectedKnowledgeBaseIds().isEmpty()) {
            wrapper.in(SuperAgentKnowledgeTopicNode::getKnowledgeBaseId, queryContext.selectedKnowledgeBaseIds());
        }
        List<SuperAgentKnowledgeTopicNode> nodes = topicNodeMapper.selectList(wrapper);
        Set<Long> preferredScopes = scopeCandidates.stream()
            .map(ScopeRouteCandidate::getScopeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (nodes.isEmpty()) {
            return deriveTopicsFromProfiles(queryContext);
        }
        List<String> routeTexts = nodes.stream()
            .map(node -> join(
                node.getTopicName(),
                node.getDescription(),
                node.getAliases(),
                node.getExamples(),
                node.getAnswerShape(),
                node.getExecutionPreference()
            ))
            .toList();
        List<Double> semanticScores = computeSemanticScores(queryContext, routeTexts);
        Map<Long, Double> lexicalScores = searchRouteIndex(queryContext, "topic", 8).hits().stream()
            .filter(hit -> hit.entityId() != null)
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::entityId, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        List<TopicRouteCandidate> candidates = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            SuperAgentKnowledgeTopicNode node = nodes.get(index);
            double semanticScore = semanticScores.get(index);
            Double routeIndexScore = lexicalScores.get(node.getId());
            double scopeRelationScore = !preferredScopes.isEmpty() && preferredScopes.contains(node.getScopeId()) ? 1D : 0D;
            double score = semanticMainScore(semanticScore)
                + lexicalAssist(routeIndexScore)
                + (scopeRelationScore * 8D);
            if (score > 0D) {
                candidates.add(new TopicRouteCandidate(
                    node.getId(),
                    node.getTopicName(),
                    node.getScopeId(),
                    scoreToBigDecimal(score),
                    buildReason(semanticScore, routeIndexScore, scopeRelationScore),
                    resolveCandidateSource(semanticScore, routeIndexScore, scopeRelationScore),
                    buildFeatures(semanticScore, routeIndexScore, "scopeRelationScore", scopeRelationScore)
                ));
            }
        }
        return candidates.stream()
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(8)
            .toList();
    }

    private List<TopicRouteCandidate> deriveTopicsFromProfiles(RouteQueryContext queryContext) {
        Map<String, TopicAccumulator> accumulatorMap = new LinkedHashMap<>();
        Map<Long, SuperAgentDocument> documentMap = listRetrievableDocuments(queryContext).stream()
            .collect(Collectors.toMap(SuperAgentDocument::getId, item -> item));
        if (documentMap.isEmpty()) {
            return List.of();
        }
        List<SuperAgentDocumentProfile> profiles = documentProfileMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocumentProfile::getProfileStatus, 2)
            .in(SuperAgentDocumentProfile::getDocumentId, documentMap.keySet()));
        for (SuperAgentDocumentProfile profile : profiles) {
            for (String topic : parseJsonArray(profile.getCoreTopics())) {
                String routeText = join(topic, profile.getDocumentSummary(), profile.getExampleQuestions());
                double semanticScore = semanticScore(queryContext, routeText);
                TopicAccumulator accumulator = accumulatorMap.computeIfAbsent(topic, TopicAccumulator::new);
                double finalScore = semanticMainScore(semanticScore);
                if (finalScore > accumulator.maxScore) {
                    accumulator.maxScore = finalScore;
                    accumulator.semanticScore = semanticScore;
                    accumulator.reason = buildReason(semanticScore, null, 0D);
                }
            }
        }
        return accumulatorMap.values().stream()
            .filter(item -> item.maxScore > 0D)
            .map(item -> new TopicRouteCandidate(
                null,
                item.topicName,
                null,
                scoreToBigDecimal(item.maxScore),
                item.reason,
                "SEMANTIC",
                buildFeatures(item.semanticScore, null, "relationScore", 0D)
            ))
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(8)
            .toList();
    }

    private DocumentRanking rankDocuments(RouteQueryContext queryContext,
                                           List<ScopeRouteCandidate> scopeCandidates,
                                           List<TopicRouteCandidate> topicCandidates) {
        List<SuperAgentDocument> documents = listRetrievableDocuments(queryContext);
        if (documents.isEmpty()) {
            return new DocumentRanking(List.of(), BigDecimal.ZERO);
        }
        Map<Long, SuperAgentDocumentProfile> profileMap = documentProfileMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
                .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
                .eq(SuperAgentDocumentProfile::getProfileStatus, 2))
            .stream()
            .collect(Collectors.toMap(SuperAgentDocumentProfile::getDocumentId, item -> item, (left, right) -> right));
        LambdaQueryWrapper<SuperAgentTopicDocumentRelation> relationWrapper = new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode());
        if (queryContext.selectedKnowledgeBaseIds() != null && !queryContext.selectedKnowledgeBaseIds().isEmpty()) {
            relationWrapper.in(SuperAgentTopicDocumentRelation::getKnowledgeBaseId, queryContext.selectedKnowledgeBaseIds());
        }
        if (queryContext.allowedDocumentIds() != null && !queryContext.allowedDocumentIds().isEmpty()) {
            relationWrapper.in(SuperAgentTopicDocumentRelation::getDocumentId, queryContext.allowedDocumentIds());
        }
        Map<Long, Map<Long, SuperAgentTopicDocumentRelation>> topicRelationMap = topicDocumentRelationMapper.selectList(relationWrapper)
            .stream()
            .filter(relation -> relation.getTopicId() != null)
            .collect(Collectors.groupingBy(SuperAgentTopicDocumentRelation::getTopicId,
                Collectors.toMap(SuperAgentTopicDocumentRelation::getDocumentId, item -> item, (left, right) -> right)));
        Long topTopicId = topicCandidates.isEmpty() ? null : topicCandidates.get(0).getTopicId();
        List<DocumentRouteMaterial> materials = documents.stream()
            .map(document -> buildDocumentRouteMaterial(document, profileMap.get(document.getId())))
            .toList();
        List<Double> semanticScores = computeSemanticScores(queryContext, materials.stream().map(DocumentRouteMaterial::routeText).toList());
        Map<Long, Double> lexicalScores = searchRouteIndex(queryContext, "document", 5).hits().stream()
            .filter(hit -> hit.documentId() != null)
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::documentId, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        List<DocumentRouteCandidate> candidates = documents.stream()
            .map(document -> buildDocumentCandidate(
                queryContext,
                document,
                profileMap.get(document.getId()),
                topTopicId,
                topicRelationMap,
                materials,
                semanticScores,
                lexicalScores
            ))
            .filter(candidate -> candidate.getScore().compareTo(BigDecimal.ZERO) > 0)
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(DOCUMENT_CANDIDATE_LIMIT)
            .toList();
        if (candidates.isEmpty()) {
            queryContext.diagnostics().markDegraded("NO_EFFECTIVE_ROUTE_CANDIDATE");
            return new DocumentRanking(
                mergeAllowedDocumentCandidates(List.of(), documents, DOCUMENT_CANDIDATE_LIMIT),
                BigDecimal.ZERO
            );
        }
        BigDecimal confidence = resolveConfidence(candidates);
        if (confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) >= 0) {
            return new DocumentRanking(candidates, confidence);
        }
        List<DocumentRouteCandidate> expandedCandidates = mergeAllowedDocumentCandidates(
            candidates,
            documents,
            DOCUMENT_CANDIDATE_LIMIT
        );
        if (expandedCandidates.size() > candidates.size()) {
            queryContext.diagnostics().markDegraded("LOW_CONFIDENCE_ALLOWED_SCOPE_EXPANSION");
        }
        return new DocumentRanking(expandedCandidates, confidence);
    }

    private DocumentRouteCandidate buildDocumentCandidate(RouteQueryContext queryContext,
                                                          SuperAgentDocument document,
                                                          SuperAgentDocumentProfile profile,
                                                          Long topTopicId,
                                                          Map<Long, Map<Long, SuperAgentTopicDocumentRelation>> topicRelationMap,
                                                          List<DocumentRouteMaterial> materials,
                                                          List<Double> semanticScores,
                                                          Map<Long, Double> lexicalScores) {
        int materialIndex = findMaterialIndex(materials, document.getId());
        double semanticScore = materialIndex >= 0 && materialIndex < semanticScores.size() ? semanticScores.get(materialIndex) : 0D;
        Double routeIndexScore = lexicalScores.get(document.getId());
        double topicRelationScore = 0D;
        double score = semanticMainScore(semanticScore)
            + lexicalAssist(routeIndexScore);
        if (topTopicId != null) {
            Map<Long, SuperAgentTopicDocumentRelation> relationMap = topicRelationMap.get(topTopicId);
            if (relationMap != null) {
                SuperAgentTopicDocumentRelation relation = relationMap.get(document.getId());
                if (relation != null && relation.getRelationScore() != null) {
                    topicRelationScore = Math.max(0D, relation.getRelationScore().doubleValue());
                    score += topicRelationScore * 20D;
                }
            }
        }
        return new DocumentRouteCandidate(
            String.valueOf(document.getId()),
            document.getDocumentName(),
            document.getLastIndexTaskId() == null ? "" : String.valueOf(document.getLastIndexTaskId()),
            scoreToBigDecimal(score),
            buildReason(semanticScore, routeIndexScore, topicRelationScore),
            resolveCandidateSource(semanticScore, routeIndexScore, topicRelationScore),
            buildFeatures(semanticScore, routeIndexScore, "topicRelationScore", topicRelationScore)
        );
    }

    private RouteQueryContext buildQueryContext(KnowledgeRouteContext context) {
        String question = context == null ? "" : context.getQuestion();
        String rewriteQuestion = context == null ? "" : context.getRewriteQuestion();
        String routingText = buildRoutingText(question, rewriteQuestion);
        RouteDiagnostics diagnostics = new RouteDiagnostics();
        float[] queryEmbedding = embedSingle(routingText, diagnostics);
        List<Long> selectedKnowledgeBaseIds = context == null || context.getSelectedKnowledgeBaseIds() == null
            ? List.of()
            : context.getSelectedKnowledgeBaseIds().stream().filter(Objects::nonNull).distinct().toList();
        List<Long> allowedDocumentIds = context == null || context.getAllowedDocumentIds() == null
            ? List.of()
            : context.getAllowedDocumentIds().stream().filter(Objects::nonNull).distinct().toList();
        return new RouteQueryContext(
            StrUtil.blankToDefault(question, ""),
            StrUtil.blankToDefault(rewriteQuestion, ""),
            routingText,
            queryEmbedding,
            selectedKnowledgeBaseIds,
            allowedDocumentIds,
            diagnostics
        );
    }

    private String buildRoutingText(String question, String rewriteQuestion) {
        String original = StrUtil.blankToDefault(question, "").trim();
        String rewritten = StrUtil.blankToDefault(rewriteQuestion, "").trim();
        if (StrUtil.isBlank(original)) {
            return rewritten;
        }
        if (StrUtil.isBlank(rewritten) || Objects.equals(original, rewritten)) {
            return original;
        }
        return original + " " + rewritten;
    }

    private List<ScopeRouteCandidate> buildScopeCandidates(List<SuperAgentKnowledgeScopeNode> nodes,
                                                           List<Double> semanticScores,
                                                           Map<Long, Double> lexicalScores) {
        List<ScopeRouteCandidate> candidates = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            SuperAgentKnowledgeScopeNode node = nodes.get(index);
            double semanticScore = semanticScores.get(index);
            Double routeIndexScore = lexicalScores.get(node.getId());
            double finalScore = semanticMainScore(semanticScore)
                + lexicalAssist(routeIndexScore);
            if (finalScore > 0D) {
                candidates.add(new ScopeRouteCandidate(
                    node.getId(),
                    node.getScopeName(),
                    scoreToBigDecimal(finalScore),
                    buildReason(semanticScore, routeIndexScore, 0D),
                    resolveCandidateSource(semanticScore, routeIndexScore, 0D),
                    buildFeatures(semanticScore, routeIndexScore, "relationScore", 0D)
                ));
            }
        }
        return candidates.stream()
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(5)
            .toList();
    }

    private DocumentRouteMaterial buildDocumentRouteMaterial(SuperAgentDocument document, SuperAgentDocumentProfile profile) {
        return new DocumentRouteMaterial(
            document.getId(),
            join(
                document.getDocumentName(),
                profile == null ? "" : profile.getDocumentSummary(),
                profile == null ? "" : profile.getCoreTopics(),
                profile == null ? "" : profile.getExampleQuestions(),
                profile == null ? "" : profile.getDocumentType()
            )
        );
    }

    private int findMaterialIndex(List<DocumentRouteMaterial> materials, Long documentId) {
        for (int index = 0; index < materials.size(); index++) {
            if (Objects.equals(materials.get(index).documentId(), documentId)) {
                return index;
            }
        }
        return -1;
    }

    private BigDecimal resolveConfidence(List<DocumentRouteCandidate> documents) {
        if (documents == null || documents.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double top = documents.get(0).getScore().doubleValue();
        double second = documents.size() > 1 ? documents.get(1).getScore().doubleValue() : 0D;
        double normalized = top / Math.max(10D, top + second + 5D);
        return scoreToBigDecimal(normalized);
    }

    private List<SuperAgentDocument> listRetrievableDocuments(RouteQueryContext queryContext) {
        LambdaQueryWrapper<SuperAgentDocument> wrapper = new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .orderByAsc(SuperAgentDocument::getId);
        if (queryContext != null && queryContext.allowedDocumentIds() != null && !queryContext.allowedDocumentIds().isEmpty()) {
            wrapper.in(SuperAgentDocument::getId, queryContext.allowedDocumentIds());
        }
        else if (queryContext != null && queryContext.selectedKnowledgeBaseIds() != null && !queryContext.selectedKnowledgeBaseIds().isEmpty()) {
            wrapper.in(SuperAgentDocument::getKnowledgeBaseId, queryContext.selectedKnowledgeBaseIds());
        }
        return documentMapper.selectList(wrapper);
    }

    private KnowledgeRouteIndexService.RouteLexicalSearchResult searchRouteIndex(RouteQueryContext queryContext,
                                                                                  String entityType,
                                                                                  int size) {
        KnowledgeRouteIndexService routeIndexService = knowledgeRouteIndexServiceProvider.getIfAvailable();
        if (routeIndexService == null) {
            queryContext.diagnostics().markDegraded("ROUTE_INDEX_NOT_CONFIGURED");
            return KnowledgeRouteIndexService.RouteLexicalSearchResult.unavailable("ROUTE_INDEX_NOT_CONFIGURED");
        }
        KnowledgeRouteIndexService.RouteLexicalSearchResult result = routeIndexService.search(
            queryContext.routingText(),
            entityType,
            size,
            queryContext.selectedKnowledgeBaseIds()
        );
        if (result == null) {
            queryContext.diagnostics().markDegraded("ROUTE_INDEX_NULL_RESULT");
            return KnowledgeRouteIndexService.RouteLexicalSearchResult.unavailable("ROUTE_INDEX_NULL_RESULT");
        }
        if (!result.available()) {
            queryContext.diagnostics().markDegraded(StrUtil.blankToDefault(result.reason(), "ROUTE_INDEX_UNAVAILABLE"));
            return result;
        }
        List<KnowledgeRouteIndexService.RouteLexicalHit> hits = result.hits();
        if (hits == null || hits.isEmpty()) {
            return KnowledgeRouteIndexService.RouteLexicalSearchResult.available(List.of());
        }
        if (queryContext.selectedKnowledgeBaseIds() != null && !queryContext.selectedKnowledgeBaseIds().isEmpty()) {
            hits = hits.stream()
                .filter(hit -> hit.knowledgeBaseId() == null || queryContext.selectedKnowledgeBaseIds().contains(hit.knowledgeBaseId()))
                .toList();
        }
        if (queryContext.allowedDocumentIds() == null || queryContext.allowedDocumentIds().isEmpty()) {
            return KnowledgeRouteIndexService.RouteLexicalSearchResult.available(hits);
        }
        List<KnowledgeRouteIndexService.RouteLexicalHit> scopedHits = hits.stream()
            .filter(hit -> hit.documentId() == null || queryContext.allowedDocumentIds().contains(hit.documentId()))
            .toList();
        return KnowledgeRouteIndexService.RouteLexicalSearchResult.available(scopedHits);
    }

    private List<String> parseJsonArray(String raw) {
        String normalized = StrUtil.blankToDefault(raw, "").trim();
        if (normalized.isBlank() || "[]".equals(normalized)) {
            return List.of();
        }
        String body = normalized.replace("[", "").replace("]", "");
        if (body.isBlank()) {
            return List.of();
        }
        return List.of(body.split(",")).stream()
            .map(item -> item.replace("\"", "").trim())
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private BigDecimal scoreToBigDecimal(double score) {
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    private String buildReason(double semanticScore, Double routeIndexScore, double relationScore) {
        boolean semanticMatched = semanticMainScore(semanticScore) > 0D;
        boolean routeIndexMatched = routeIndexScore != null && routeIndexScore > 0D;
        boolean relationMatched = relationScore > 0D;
        if (semanticMatched && routeIndexMatched && relationMatched) {
            return "语义、路由索引与持久化关系特征共同召回";
        }
        if (semanticMatched && routeIndexMatched) {
            return "语义与路由索引特征共同召回";
        }
        if (routeIndexMatched && relationMatched) {
            return "路由索引与持久化关系特征共同召回";
        }
        if (semanticMatched && relationMatched) {
            return "语义与持久化关系特征共同召回";
        }
        if (routeIndexMatched) {
            return "由路由索引 BM25 特征召回";
        }
        if (semanticMatched) {
            return "由持久化画像的语义相似度召回";
        }
        if (relationMatched) {
            return "由持久化关系特征召回";
        }
        return "没有形成有效路由特征";
    }

    private String join(String... values) {
        return Arrays.stream(values)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" "));
    }

    private float[] embedSingle(String text, RouteDiagnostics diagnostics) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        EmbeddingPort embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            diagnostics.markDegraded("SEMANTIC_ROUTE_NOT_CONFIGURED");
            return null;
        }
        try {
            return embeddingModel.embed(text.trim());
        }
        catch (Exception exception) {
            diagnostics.markDegraded("SEMANTIC_QUERY_EMBEDDING_UNAVAILABLE");
            log.warn("知识路由生成问题向量失败，将仅使用路由索引: text='{}'", StrUtil.maxLength(text, 120), exception);
            return null;
        }
    }

    private List<Double> computeSemanticScores(RouteQueryContext queryContext, List<String> routeTexts) {
        if (!queryContext.semanticEnabled() || routeTexts == null || routeTexts.isEmpty()) {
            return routeTexts == null ? List.of() : routeTexts.stream().map(item -> 0D).toList();
        }
        EmbeddingPort embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            queryContext.diagnostics().markDegraded("SEMANTIC_ROUTE_NOT_CONFIGURED");
            return routeTexts.stream().map(item -> 0D).toList();
        }
        try {
            List<String> normalizedRouteTexts = routeTexts.stream()
                .map(item -> StrUtil.blankToDefault(item, ""))
                .toList();
            List<Double> scores = new ArrayList<>(normalizedRouteTexts.size());
            for (int index = 0; index < normalizedRouteTexts.size(); index++) {
                scores.add(0D);
            }
            int totalBatchCount = (normalizedRouteTexts.size() + ROUTE_EMBEDDING_BATCH_SIZE - 1) / ROUTE_EMBEDDING_BATCH_SIZE;
            for (int startIndex = 0; startIndex < normalizedRouteTexts.size(); startIndex += ROUTE_EMBEDDING_BATCH_SIZE) {
                int endIndex = Math.min(startIndex + ROUTE_EMBEDDING_BATCH_SIZE, normalizedRouteTexts.size());
                List<String> currentBatch = normalizedRouteTexts.subList(startIndex, endIndex);
                int currentBatchIndex = (startIndex / ROUTE_EMBEDDING_BATCH_SIZE) + 1;
                log.debug("知识路由候选向量分批计算: batchIndex={}/{}, candidateRange=[{}, {}], batchSize={}",
                    currentBatchIndex, totalBatchCount, startIndex + 1, endIndex, currentBatch.size());
                List<float[]> embeddings = embeddingModel.embed(currentBatch);
                if (embeddings == null || embeddings.size() != currentBatch.size()) {
                    queryContext.diagnostics().markDegraded("SEMANTIC_CANDIDATE_EMBEDDING_INVALID");
                    return routeTexts.stream().map(item -> 0D).toList();
                }
                for (int batchIndex = 0; batchIndex < embeddings.size(); batchIndex++) {
                    scores.set(startIndex + batchIndex, cosineSimilarity(queryContext.queryEmbedding(), embeddings.get(batchIndex)));
                }
            }
            return scores;
        }
        catch (Exception exception) {
            queryContext.diagnostics().markDegraded("SEMANTIC_CANDIDATE_EMBEDDING_UNAVAILABLE");
            log.warn("知识路由生成候选向量失败，将仅使用路由索引: candidateCount={}", routeTexts.size(), exception);
            return routeTexts.stream().map(item -> 0D).toList();
        }
    }

    private double semanticScore(RouteQueryContext queryContext, String routeText) {
        if (!queryContext.semanticEnabled()) {
            return 0D;
        }
        float[] routeEmbedding = embedSingle(routeText, queryContext.diagnostics());
        if (routeEmbedding == null) {
            return 0D;
        }
        return cosineSimilarity(queryContext.queryEmbedding(), routeEmbedding);
    }

    private double semanticMainScore(double semanticScore) {
        if (semanticScore <= 0.20D) {
            return 0D;
        }
        return (semanticScore - 0.20D) * 50D;
    }

    private double lexicalAssist(Double lexicalScore) {
        if (lexicalScore == null || lexicalScore <= 0D) {
            return 0D;
        }
        return Math.min(10D, lexicalScore * 1.6D);
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm <= 0D || rightNorm <= 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private List<DocumentRouteCandidate> mergeAllowedDocumentCandidates(List<DocumentRouteCandidate> rankedCandidates,
                                                                        List<SuperAgentDocument> documents,
                                                                        int limit) {
        int boundedLimit = Math.max(1, limit);
        List<DocumentRouteCandidate> mergedCandidates = new ArrayList<>();
        Set<String> rankedDocumentIds = new LinkedHashSet<>();
        if (rankedCandidates != null) {
            rankedCandidates.stream()
                .filter(Objects::nonNull)
                .limit(boundedLimit)
                .forEach(candidate -> {
                    mergedCandidates.add(candidate);
                    if (StrUtil.isNotBlank(candidate.getDocumentId())) {
                        rankedDocumentIds.add(candidate.getDocumentId());
                    }
                });
        }
        if (documents == null || documents.isEmpty() || mergedCandidates.size() >= boundedLimit) {
            return List.copyOf(mergedCandidates);
        }
        for (SuperAgentDocument document : documents) {
            if (document == null || document.getId() == null || document.getLastIndexTaskId() == null) {
                continue;
            }
            String documentId = String.valueOf(document.getId());
            if (rankedDocumentIds.contains(documentId)) {
                continue;
            }
            mergedCandidates.add(new DocumentRouteCandidate(
                String.valueOf(document.getId()),
                StrUtil.blankToDefault(document.getDocumentName(), ""),
                String.valueOf(document.getLastIndexTaskId()),
                BigDecimal.ZERO,
                "未形成有效路由特征，按允许文档范围有界扩展候选池",
                "ALLOWED_DOCUMENT_SCOPE",
                new LinkedHashMap<>(Map.of("allowedScopeFallback", BigDecimal.ONE))
            ));
            rankedDocumentIds.add(documentId);
            if (mergedCandidates.size() >= boundedLimit) {
                break;
            }
        }
        return List.copyOf(mergedCandidates);
    }

    private Map<String, BigDecimal> buildFeatures(double semanticScore,
                                                  Double routeIndexScore,
                                                  String relationFeatureName,
                                                  double relationScore) {
        Map<String, BigDecimal> features = new LinkedHashMap<>();
        features.put("semanticScore", scoreToBigDecimal(Math.max(0D, semanticScore)));
        features.put("routeIndexScore", scoreToBigDecimal(routeIndexScore == null ? 0D : Math.max(0D, routeIndexScore)));
        features.put(relationFeatureName, scoreToBigDecimal(Math.max(0D, relationScore)));
        return features;
    }

    private String resolveCandidateSource(double semanticScore, Double routeIndexScore, double relationScore) {
        int sourceCount = 0;
        String source = "NONE";
        if (semanticMainScore(semanticScore) > 0D) {
            source = "SEMANTIC";
            sourceCount++;
        }
        if (routeIndexScore != null && routeIndexScore > 0D) {
            source = "ROUTE_INDEX";
            sourceCount++;
        }
        if (relationScore > 0D) {
            source = "PERSISTED_RELATION";
            sourceCount++;
        }
        return sourceCount > 1 ? "COMPOSITE" : source;
    }

    private String resolveDecisionSource(List<DocumentRouteCandidate> documentCandidates) {
        if (documentCandidates == null || documentCandidates.isEmpty()) {
            return "NONE";
        }
        List<String> sources = documentCandidates.stream()
            .map(DocumentRouteCandidate::getSource)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        return sources.size() == 1 ? sources.get(0) : "COMPOSITE";
    }

    private String resolveDecisionReason(List<DocumentRouteCandidate> documentCandidates, BigDecimal confidence) {
        if (documentCandidates == null || documentCandidates.isEmpty()) {
            return "没有找到可用候选文档";
        }
        String topReason = StrUtil.blankToDefault(documentCandidates.get(0).getReason(), "");
        if (confidence == null) {
            return topReason;
        }
        if (confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0) {
            return StrUtil.blankToDefault(topReason, "低置信度，已进入保守扩范围候选");
        }
        return topReason;
    }

    private String writeScopeJson(List<ScopeRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"scopeId\":\"" + nullToEmpty(item.getScopeId()) + "\",\"scopeName\":\"" + escapeJson(item.getScopeName()) + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String writeTopicJson(List<TopicRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"topicId\":\"" + nullToEmpty(item.getTopicId()) + "\",\"topicName\":\"" + escapeJson(item.getTopicName()) + "\",\"scopeId\":\"" + nullToEmpty(item.getScopeId()) + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String writeDocumentJson(List<DocumentRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"documentId\":\"" + item.getDocumentId() + "\",\"documentName\":\"" + escapeJson(item.getDocumentName()) + "\",\"lastIndexTaskId\":\"" + item.getLastIndexTaskId() + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String writeStringJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(value -> "\"" + escapeJson(value) + "\"")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String escapeJson(String text) {
        return StrUtil.blankToDefault(text, "").replace("\"", "\\\"");
    }

    private static final class TopicAccumulator {
        private final String topicName;
        private double maxScore;
        private double semanticScore;
        private String reason = "";

        private TopicAccumulator(String topicName) {
            this.topicName = topicName;
        }
    }

    private String nullToEmpty(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record RouteQueryContext(String originalQuestion,
                                     String rewriteQuestion,
                                     String routingText,
                                     float[] queryEmbedding,
                                     List<Long> selectedKnowledgeBaseIds,
                                     List<Long> allowedDocumentIds,
                                     RouteDiagnostics diagnostics) {
        private boolean semanticEnabled() {
            return queryEmbedding != null && queryEmbedding.length > 0;
        }
    }

    private static final class RouteDiagnostics {

        private final LinkedHashSet<String> degradedReasons = new LinkedHashSet<>();

        private void markDegraded(String reason) {
            if (StrUtil.isNotBlank(reason)) {
                degradedReasons.add(reason);
            }
        }

        private boolean isDegraded() {
            return !degradedReasons.isEmpty();
        }

        private List<String> degradedReasons() {
            return List.copyOf(degradedReasons);
        }
    }

    private record DocumentRouteMaterial(Long documentId, String routeText) {
    }

    private record DocumentRanking(List<DocumentRouteCandidate> candidates, BigDecimal confidence) {

        private DocumentRanking {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            confidence = confidence == null ? BigDecimal.ZERO : confidence;
        }
    }
}
