package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCommunity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEntity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEvidence;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelation;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCommunityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEntityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEvidenceMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationMapper;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagQueryCatalog;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagQueryPlanAdvice;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagSearchExecution;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagSearchResult;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagCrossDocumentIndexService;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagQueryPlanAdvisor;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagSearchService;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCrossDocumentIndex;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCrossDocumentIndex.CanonicalEntityGroup;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCrossDocumentIndex.CrossDocumentCommunity;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCrossDocumentIndex.RelationGroup;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCrossDocumentIndexSupport;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagRelationAuthority;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchRequest;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchObservation;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchObservation.FallbackReason;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchObservation.ResultSource;
import org.smartledge.enums.BusinessStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GraphRagSearchServiceImpl implements GraphRagSearchService {

    private static final Pattern ENGLISH_TERM_PATTERN = Pattern.compile("\\b[A-Za-z][A-Za-z0-9._/-]{1,40}\\b");
    private static final double ADVISOR_CONFIDENCE_THRESHOLD = 0.58D;
    private static final int CATALOG_ENTITY_LIMIT = 80;
    private static final int CATALOG_RELATION_LIMIT = 120;
    private static final int CATALOG_COMMUNITY_LIMIT = 40;
    private static final String JAVA_QUERY_PROFILE_SOURCE = "java.graph_query_profile.v2";
    private static final String ADVISOR_QUERY_PROFILE_SOURCE = "llm.controlled.query_plan.v1";
    private final SuperAgentKgEntityMapper entityMapper;

    private final SuperAgentKgRelationMapper relationMapper;

    private final SuperAgentKgEvidenceMapper evidenceMapper;

    private final SuperAgentKgCommunityMapper communityMapper;

    private final ObjectMapper objectMapper;

    private final GraphRagQueryPlanAdvisor queryPlanAdvisor;

    private final GraphRagCrossDocumentIndexService crossDocumentIndexService;

    private final GraphRagCrossDocumentIndexSupport crossDocumentIndexSupport;

    @Autowired
    public GraphRagSearchServiceImpl(SuperAgentKgEntityMapper entityMapper,
                                     SuperAgentKgRelationMapper relationMapper,
                                     SuperAgentKgEvidenceMapper evidenceMapper,
                                     SuperAgentKgCommunityMapper communityMapper,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<GraphRagQueryPlanAdvisor> queryPlanAdvisorProvider,
                                     ObjectProvider<GraphRagCrossDocumentIndexService> crossDocumentIndexServiceProvider,
                                     GraphRagCrossDocumentIndexSupport crossDocumentIndexSupport) {
        this(
            entityMapper,
            relationMapper,
            evidenceMapper,
            communityMapper,
            objectMapper,
            queryPlanAdvisorProvider == null ? null : queryPlanAdvisorProvider.getIfAvailable(),
            crossDocumentIndexServiceProvider == null ? null : crossDocumentIndexServiceProvider.getIfAvailable(),
            crossDocumentIndexSupport
        );
    }

    public GraphRagSearchServiceImpl(SuperAgentKgEntityMapper entityMapper,
                                     SuperAgentKgRelationMapper relationMapper,
                                     SuperAgentKgEvidenceMapper evidenceMapper,
                                     SuperAgentKgCommunityMapper communityMapper,
                                     ObjectMapper objectMapper) {
        this(entityMapper, relationMapper, evidenceMapper, communityMapper, objectMapper, (GraphRagQueryPlanAdvisor) null);
    }

    GraphRagSearchServiceImpl(SuperAgentKgEntityMapper entityMapper,
                              SuperAgentKgRelationMapper relationMapper,
                              SuperAgentKgEvidenceMapper evidenceMapper,
                              SuperAgentKgCommunityMapper communityMapper,
                              ObjectMapper objectMapper,
                              GraphRagQueryPlanAdvisor queryPlanAdvisor) {
        this(
            entityMapper,
            relationMapper,
            evidenceMapper,
            communityMapper,
            objectMapper,
            queryPlanAdvisor,
            null,
            new GraphRagCrossDocumentIndexSupport(objectMapper)
        );
    }

    GraphRagSearchServiceImpl(SuperAgentKgEntityMapper entityMapper,
                              SuperAgentKgRelationMapper relationMapper,
                              SuperAgentKgEvidenceMapper evidenceMapper,
                              SuperAgentKgCommunityMapper communityMapper,
                              ObjectMapper objectMapper,
                              GraphRagQueryPlanAdvisor queryPlanAdvisor,
                              GraphRagCrossDocumentIndexService crossDocumentIndexService,
                              GraphRagCrossDocumentIndexSupport crossDocumentIndexSupport) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.evidenceMapper = evidenceMapper;
        this.communityMapper = communityMapper;
        this.objectMapper = objectMapper;
        this.queryPlanAdvisor = queryPlanAdvisor;
        this.crossDocumentIndexService = crossDocumentIndexService;
        this.crossDocumentIndexSupport = crossDocumentIndexSupport == null
            ? new GraphRagCrossDocumentIndexSupport(objectMapper)
            : crossDocumentIndexSupport;
    }

    @Override
    @Transactional(readOnly = true)
    public GraphRagSearchExecution search(GraphRagSearchRequest request) {
        if (request == null || CollUtil.isEmpty(request.documentScope()) || request.topK() <= 0) {
            return new GraphRagSearchExecution(List.of(), GraphRagSearchObservation.withoutHints(List.of()));
        }

        List<Long> documentIds = request.documentScope();
        List<Long> taskIds = request.taskScope();
        int topK = request.topK();
        int maxHops = request.maxHops();
        List<SuperAgentKgEntity> allEntities = listEntities(documentIds, taskIds);
        EntityHintSeeds entityHintSeeds = resolveEntityHintSeeds(request.entityHints(), allEntities);
        FallbackReason fallbackReason = fallbackReason(entityHintSeeds);
        String question = entityHintSeeds.usedSeeds().isEmpty()
            ? StrUtil.blankToDefault(request.originalQuestion(), request.executionQuery())
            : StrUtil.blankToDefault(request.executionQuery(), request.originalQuestion());
        if (StrUtil.isBlank(question)) {
            return outcome(List.of(), entityHintSeeds, fallbackReason);
        }
        List<String> terms = extractTerms(question);
        String normalizedQuestion = normalize(question);
        QueryProfile queryProfile = withEntityHintSeeds(
            analyzeQuery(question, terms, maxHops),
            entityHintSeeds
        );
        Map<Long, SuperAgentKgEntity> entityMap = allEntities.stream()
            .collect(Collectors.toMap(SuperAgentKgEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        GraphRagCrossDocumentIndex crossDocumentIndex = loadCrossDocumentIndex(documentIds, taskIds, allEntities, List.of(), List.of());
        List<SuperAgentKgCommunity> allCommunities = listCommunities(documentIds, taskIds);
        List<SuperAgentKgRelation> loadedRelations = List.of();
        queryProfile = withJavaFocusEntities(queryProfile, allEntities, normalizedQuestion);
        if (shouldAskAdvisor(question, queryProfile, allEntities, allCommunities)) {
            loadedRelations = listAllRelations(documentIds, taskIds);
            crossDocumentIndex = ensureCrossDocumentIndex(documentIds, taskIds, allEntities, loadedRelations, List.of(), crossDocumentIndex);
            AdvisorProfileApplication advisorApplication = applyAdvisorProfile(
                question,
                queryProfile,
                allEntities,
                loadedRelations,
                allCommunities,
                topK,
                maxHops
            );
            queryProfile = advisorApplication.profile();
            if (advisorApplication.failed()) {
                fallbackReason = FallbackReason.ADVISOR_FAILURE;
                question = StrUtil.blankToDefault(request.originalQuestion(), request.executionQuery());
                terms = extractTerms(question);
                normalizedQuestion = normalize(question);
                queryProfile = withJavaFocusEntities(
                    withEntityHintSeeds(analyzeQuery(question, terms, maxHops), entityHintSeeds),
                    allEntities,
                    normalizedQuestion
                );
            }
        }
        QueryProfile effectiveQueryProfile = queryProfile;
        String effectiveNormalizedQuestion = normalizedQuestion;
        List<String> effectiveTerms = terms;
        List<GraphRagSearchResult> communityResults = new ArrayList<>(
            searchCommunityReports(documentIds, taskIds, allCommunities, topK, effectiveNormalizedQuestion, effectiveTerms,
                effectiveQueryProfile)
        );
        List<ScoredEntity> seedEntities = allEntities.stream()
            .map(entity -> new ScoredEntity(entity, scoreEntity(entity, effectiveNormalizedQuestion, effectiveTerms,
                effectiveQueryProfile)))
            .filter(item -> item.score() > 0D)
            .sorted(Comparator.comparingDouble(ScoredEntity::score).reversed())
            .limit(Math.max(topK * 4L, 12L))
            .toList();
        boolean explicitRelationSeed = CollUtil.isNotEmpty(effectiveQueryProfile.relationTypes())
            || CollUtil.isNotEmpty(effectiveQueryProfile.relationIds());
        List<SuperAgentKgRelation> allRelations = explicitRelationSeed
            ? loadedOrQueryRelations(documentIds, taskIds, loadedRelations)
            : List.of();
        List<ScoredRelation> seedRelations = allRelations.stream()
            .map(relation -> new ScoredRelation(
                relation,
                scoreRelation(relation, entityMap, effectiveNormalizedQuestion, effectiveTerms, effectiveQueryProfile)
            ))
            .filter(item -> item.score() > 0D)
            .sorted(Comparator.comparingDouble(ScoredRelation::score).reversed())
            .limit(Math.max(topK * 4L, 12L))
            .toList();
        if (seedEntities.isEmpty() && seedRelations.isEmpty()) {
            return outcome(limitResults(communityResults, topK), entityHintSeeds, fallbackReason);
        }

        Map<Long, Double> seedScoreMap = seedEntities.stream()
            .collect(Collectors.toMap(item -> item.entity().getId(), ScoredEntity::score, Math::max, LinkedHashMap::new));
        Map<Long, Double> relationSeedScoreMap = seedRelations.stream()
            .collect(Collectors.toMap(item -> item.relation().getId(), ScoredRelation::score, Math::max, LinkedHashMap::new));
        seedScoreMap = expandCanonicalSeedScores(seedScoreMap, crossDocumentIndex);
        Map<Long, EntityTrace> seedTraceMap = expandCanonicalSeedTraces(seedEntities, crossDocumentIndex);
        Set<Long> frontierEntityIds = seedEntities.stream()
            .map(item -> item.entity().getId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (frontierEntityIds.isEmpty()) {
            frontierEntityIds.addAll(seedRelations.stream()
                .flatMap(item -> relatedEntityIds(List.of(item.relation())).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        frontierEntityIds.addAll(seedScoreMap.keySet());
        frontierEntityIds = expandCanonicalEntityIds(frontierEntityIds, crossDocumentIndex);

        Map<Long, SuperAgentKgRelation> relationMap = new LinkedHashMap<>();
        Map<Long, Integer> relationHopMap = new LinkedHashMap<>();
        Map<Long, RelationTrace> relationTraceMap = new LinkedHashMap<>();
        seedRelations.forEach(item -> {
            relationMap.put(item.relation().getId(), item.relation());
            relationHopMap.put(item.relation().getId(), 0);
            relationTraceMap.put(item.relation().getId(),
                relationTrace(item.relation(), entityMap, seedTraceMap, 0, effectiveQueryProfile.sourceText()));
        });
        List<SuperAgentKgRelation> oneHopRelations = listRelations(documentIds, taskIds, frontierEntityIds);
        oneHopRelations.forEach(relation -> {
            RelationTrace trace = relationTrace(relation, entityMap, seedTraceMap, 1, effectiveQueryProfile.sourceText());
            mergeExpandedRelationTrace(relationMap, relationHopMap, relationTraceMap, relation, 1, trace, effectiveQueryProfile);
        });

        if (Math.max(effectiveQueryProfile.maxHops(), 1) >= 2 && !oneHopRelations.isEmpty()) {
            Map<Long, EntityTrace> neighborTraceMap = oneHopEntityTraces(oneHopRelations, entityMap, seedTraceMap);
            Set<Long> neighborEntityIds = new LinkedHashSet<>(neighborTraceMap.keySet());
            neighborEntityIds.removeAll(frontierEntityIds);
            if (!neighborEntityIds.isEmpty()) {
                listRelations(documentIds, taskIds, neighborEntityIds)
                    .forEach(relation -> {
                        RelationTrace trace = relationTrace(relation, entityMap, neighborTraceMap, 2, effectiveQueryProfile.sourceText());
                        mergeExpandedRelationTrace(relationMap, relationHopMap, relationTraceMap, relation, 2, trace, effectiveQueryProfile);
                    });
            }
        }

        Set<Long> relationIds = relationMap.keySet();
        Set<Long> evidenceEntityIds = new LinkedHashSet<>(frontierEntityIds);
        evidenceEntityIds.addAll(relatedEntityIds(relationMap.values()));
        List<SuperAgentKgEvidence> evidences = listEvidences(documentIds, taskIds, evidenceEntityIds, relationIds);
        if (evidences.isEmpty()) {
            return outcome(limitResults(communityResults, topK), entityHintSeeds, fallbackReason);
        }

        crossDocumentIndex = ensureCrossDocumentIndex(documentIds, taskIds, allEntities, relationMap.values(), evidences, crossDocumentIndex);
        CommunityRankContext communityRankContext = communityRankContext(documentIds, taskIds, crossDocumentIndex, evidences,
            effectiveQueryProfile);
        communityResults.addAll(searchCrossDocumentCommunities(crossDocumentIndex, communityRankContext, topK,
            effectiveNormalizedQuestion, effectiveTerms, effectiveQueryProfile));
        Map<Long, GraphRagSearchResult> resultMap = new LinkedHashMap<>();
        for (SuperAgentKgEvidence evidence : evidences) {
            if (!isGraphEvidenceUsable(evidence)) {
                continue;
            }
            GraphRagSearchResult result = toResult(evidence, entityMap, relationMap, relationHopMap,
                relationTraceMap, seedScoreMap, relationSeedScoreMap, crossDocumentIndex, communityRankContext,
                effectiveTerms, effectiveQueryProfile);
            if (result == null) {
                continue;
            }
            resultMap.merge(evidence.getId(), result,
                (left, right) -> left.getScore() >= right.getScore() ? left : right);
        }

        List<GraphRagSearchResult> results = new ArrayList<>(resultMap.values());
        results.addAll(communityResults);
        return outcome(limitResults(results, topK), entityHintSeeds, fallbackReason);
    }

    private GraphRagSearchExecution outcome(List<GraphRagSearchResult> results,
                                            EntityHintSeeds entityHintSeeds,
                                            FallbackReason fallbackReason) {
        LinkedHashSet<ResultSource> resultSources = new LinkedHashSet<>();
        for (GraphRagSearchResult result : results) {
            if (result == null) {
                continue;
            }
            if (result.getRelationId() != null) {
                resultSources.add(ResultSource.RELATION_EVIDENCE);
            }
            else if (isCommunityResult(result)) {
                resultSources.add(ResultSource.COMMUNITY_REPORT);
            }
            else if (result.getEntityId() != null) {
                resultSources.add(ResultSource.ENTITY_EVIDENCE);
            }
        }
        GraphRagSearchObservation observation = new GraphRagSearchObservation(
            entityHintSeeds.hintsArrived(),
            entityHintSeeds.receivedHints(),
            entityHintSeeds.usedSeeds(),
            fallbackReason != FallbackReason.NONE,
            fallbackReason,
            List.copyOf(resultSources)
        );
        return new GraphRagSearchExecution(results, observation);
    }

    private FallbackReason fallbackReason(EntityHintSeeds entityHintSeeds) {
        if (!entityHintSeeds.hintsArrived()) {
            return FallbackReason.EMPTY_HINTS;
        }
        if (entityHintSeeds.normalizedHints().isEmpty()) {
            return FallbackReason.INVALID_HINTS;
        }
        if (entityHintSeeds.usedSeeds().isEmpty()) {
            return FallbackReason.ENTITY_NOT_FOUND;
        }
        return FallbackReason.NONE;
    }

    private List<GraphRagSearchResult> limitResults(List<GraphRagSearchResult> results, int topK) {
        if (CollUtil.isEmpty(results) || topK <= 0) {
            return List.of();
        }
        return results.stream()
            .sorted(Comparator
                .comparingInt(this::graphRagResultPriority).reversed()
                .thenComparing(Comparator.comparingDouble(GraphRagSearchResult::getScore).reversed()))
            .limit(topK)
            .toList();
    }

    private int graphRagResultPriority(GraphRagSearchResult result) {
        if (result == null) {
            return 0;
        }
        boolean hasSourceQuote = result.getEvidenceId() != null && StrUtil.isNotBlank(result.getQuoteText());
        if (result.getRelationId() != null) {
            if (hasSourceQuote) {
                return 5;
            }
            if (result.getEvidenceId() != null) {
                return 4;
            }
            return 3;
        }
        if (result.getEntityId() != null) {
            return hasSourceQuote ? 2 : 1;
        }
        if (isCommunityResult(result)) {
            if (hasSourceQuote && StrUtil.isNotBlank(result.getRelationGroupKey())) {
                return 3;
            }
            return hasSourceQuote ? 2 : 0;
        }
        return 1;
    }

    private boolean isCommunityResult(GraphRagSearchResult result) {
        if (result == null) {
            return false;
        }
        return result.getCommunityId() != null
            || StrUtil.isNotBlank(result.getCrossDocumentCommunityKey())
            || StrUtil.isNotBlank(result.getCommunityTitle())
            || StrUtil.isNotBlank(result.getCommunitySummary());
    }

    private GraphRagCrossDocumentIndex loadCrossDocumentIndex(List<Long> documentIds,
                                                              List<Long> taskIds,
                                                              List<SuperAgentKgEntity> entities,
                                                              Collection<SuperAgentKgRelation> relations,
                                                              List<SuperAgentKgEvidence> evidences) {
        if (crossDocumentIndexService != null) {
            try {
                GraphRagCrossDocumentIndex index = crossDocumentIndexService.loadIndex(documentIds, taskIds);
                if (index != null && index.hasCanonicalGroups()) {
                    return index;
                }
            }
            catch (RuntimeException exception) {
                log.warn("GraphRAG 跨文档持久化读模型读取失败，改用同源 KG 临时读模型: message={}", exception.getMessage());
            }
        }
        return buildRuntimeCrossDocumentIndex(entities, relations, evidences);
    }

    private GraphRagCrossDocumentIndex ensureCrossDocumentIndex(List<Long> documentIds,
                                                               List<Long> taskIds,
                                                               List<SuperAgentKgEntity> entities,
                                                               Collection<SuperAgentKgRelation> relations,
                                                               List<SuperAgentKgEvidence> evidences,
                                                               GraphRagCrossDocumentIndex currentIndex) {
        if (currentIndex != null && currentIndex.hasCanonicalGroups() && currentIndex.hasRelationGroups()) {
            return currentIndex;
        }
        if (currentIndex != null && currentIndex.hasCanonicalGroups() && CollUtil.isEmpty(relations)) {
            return currentIndex;
        }
        if (crossDocumentIndexService != null && currentIndex != null && currentIndex.hasCanonicalGroups()) {
            return currentIndex;
        }
        GraphRagCrossDocumentIndex runtimeIndex = buildRuntimeCrossDocumentIndex(entities, relations, evidences);
        if (runtimeIndex.hasCanonicalGroups() || runtimeIndex.hasRelationGroups()) {
            return runtimeIndex;
        }
        return currentIndex == null ? GraphRagCrossDocumentIndex.empty() : currentIndex;
    }

    private GraphRagCrossDocumentIndex buildRuntimeCrossDocumentIndex(List<SuperAgentKgEntity> entities,
                                                                      Collection<SuperAgentKgRelation> relations,
                                                                      List<SuperAgentKgEvidence> evidences) {
        return crossDocumentIndexSupport.build(
            entities == null ? List.of() : entities,
            relations == null ? List.of() : relations,
            evidences == null ? List.of() : evidences
        );
    }

    private List<GraphRagSearchResult> searchCommunityReports(List<Long> documentIds,
                                                              List<Long> taskIds,
                                                              List<SuperAgentKgCommunity> communities,
                                                              int topK,
                                                              String normalizedQuestion,
                                                              List<String> terms,
                                                              QueryProfile queryProfile) {
        if (communities.isEmpty()) {
            return List.of();
        }
        List<ScoredCommunity> scoredCommunities = communities.stream()
            .map(community -> new ScoredCommunity(community, scoreCommunity(community, normalizedQuestion, terms, queryProfile)))
            .filter(item -> item.score() > 0D)
            .sorted(Comparator.comparingDouble(ScoredCommunity::score).reversed())
            .limit(Math.max(topK, 3))
            .toList();
        if (scoredCommunities.isEmpty()) {
            return List.of();
        }

        Set<Long> evidenceIds = scoredCommunities.stream()
            .flatMap(item -> readLongList(item.community().getEvidenceIdsJson()).stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (evidenceIds.isEmpty()) {
            return List.of();
        }
        Map<Long, SuperAgentKgEvidence> evidenceMap = listEvidencesByIds(documentIds, taskIds, evidenceIds).stream()
            .collect(Collectors.toMap(SuperAgentKgEvidence::getId, item -> item, (left, right) -> left, LinkedHashMap::new));

        List<GraphRagSearchResult> results = new ArrayList<>();
        for (ScoredCommunity scoredCommunity : scoredCommunities) {
            SuperAgentKgCommunity community = scoredCommunity.community();
            SuperAgentKgEvidence representativeEvidence = readLongList(community.getEvidenceIdsJson()).stream()
                .map(evidenceMap::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
            if (representativeEvidence == null) {
                continue;
            }
            double rankBoost = communityRankBoost(community);
            GraphRagSearchResult.GraphRagSearchResultBuilder builder = baseResult(representativeEvidence)
                .communityId(community.getId())
                .communityTitle(community.getTitle())
                .communitySummary(community.getSummary())
                .evidenceId(representativeEvidence.getId())
                .quoteText(representativeEvidence.getQuoteText())
                .graphPath("社区报告：" + StrUtil.blankToDefault(community.getTitle(), "未命名图谱社区"))
                .hopCount(0)
                .rankBoost(rankBoost)
                .score(scoredCommunity.score()
                    + graphRankScore(rankBoost)
                    + evidenceBoost(representativeEvidence.getQuoteText(), terms));
            results.add(withQueryProfile(builder, queryProfile).build());
        }
        return results;
    }

    private List<GraphRagSearchResult> searchCrossDocumentCommunities(GraphRagCrossDocumentIndex index,
                                                                      CommunityRankContext communityRankContext,
                                                                      int topK,
                                                                      String normalizedQuestion,
                                                                      List<String> terms,
                                                                      QueryProfile queryProfile) {
        if (index == null || !index.hasCommunities()) {
            return List.of();
        }
        if (queryProfile == null) {
            return List.of();
        }
        boolean hasCommunityPlan = queryProfile.communityQuestion()
            || CollUtil.isNotEmpty(queryProfile.communityIds());
        boolean hasFocusedTopic = CollUtil.isNotEmpty(queryProfile.focusEntities())
            || CollUtil.isNotEmpty(queryProfile.entityIds())
            || CollUtil.isNotEmpty(queryProfile.entityNames())
            || CollUtil.isNotEmpty(queryProfile.entitiesFromQuery());
        if (!hasCommunityPlan && !hasFocusedTopic) {
            return List.of();
        }
        if (communityRankContext == null) {
            return List.of();
        }
        List<ScoredCrossDocumentCommunity> scoredCommunities = index.communityByKey().values().stream()
            .map(community -> new ScoredCrossDocumentCommunity(
                community,
                communityRankProfile(community, communityRankContext)
            ))
            .filter(item -> item.score() > 0D)
            .sorted(Comparator.comparingDouble(ScoredCrossDocumentCommunity::score).reversed())
            .limit(Math.max(topK, 3))
            .toList();
        if (scoredCommunities.isEmpty()) {
            return List.of();
        }

        Map<Long, List<RelationGroup>> relationGroupsByEvidenceId = relationGroupsByEvidenceId(index);
        List<GraphRagSearchResult> results = new ArrayList<>();
        for (ScoredCrossDocumentCommunity scoredCommunity : scoredCommunities) {
            CrossDocumentCommunity community = scoredCommunity.community();
            RepresentativeCommunityEvidence representative = selectRepresentativeCommunityEvidence(
                community,
                communityRankContext.evidenceMap(),
                relationGroupsByEvidenceId,
                terms,
                queryProfile
            );
            SuperAgentKgEvidence representativeEvidence = representative == null ? null : representative.evidence();
            if (representativeEvidence == null) {
                continue;
            }
            RelationGroup representativeGroup = representative.relationGroup();
            if (!hasCommunityPlan && !isStrongCommunitySupplement(representativeGroup)) {
                continue;
            }
            double rankBoost = community.rankProfile() == null ? 0D : community.rankProfile().rankBoost();
            GraphRagSearchResult.GraphRagSearchResultBuilder builder = baseResult(representativeEvidence)
                .communityId(community.id())
                .communityTitle(community.title())
                .communitySummary(community.summary())
                .evidenceId(representativeEvidence.getId())
                .quoteText(representativeEvidence.getQuoteText())
                .graphPath("跨文档社区：" + StrUtil.blankToDefault(community.title(), community.key()))
                .hopCount(0)
                .rankBoost(rankBoost)
                .score(scoredCommunity.score()
                    + graphRankScore(rankBoost)
                    + representative.score());
            builder = withQueryProfile(builder, queryProfile);
            builder = withCommunityRankProfile(builder, scoredCommunity.rankProfile());
            results.add(withCrossDocumentCommunity(
                    withRelationGroup(builder, representativeGroup),
                    community
                ).build());
        }
        return results;
    }

    private boolean isStrongCommunitySupplement(RelationGroup representativeGroup) {
        return representativeGroup != null
            && representativeGroup.documentCount() > 1
            && representativeGroup.evidenceCount() > 1;
    }

    private CommunityRankContext communityRankContext(List<Long> documentIds,
                                                      List<Long> taskIds,
                                                      GraphRagCrossDocumentIndex index,
                                                      List<SuperAgentKgEvidence> loadedEvidences,
                                                      QueryProfile queryProfile) {
        if (index == null || !index.hasCommunities()) {
            return null;
        }
        Map<Long, SuperAgentKgEvidence> evidenceMap = safeEvidenceMap(loadedEvidences);
        Set<Long> communityEvidenceIds = new LinkedHashSet<>();
        for (CrossDocumentCommunity community : index.communityByKey().values()) {
            if (community != null && CollUtil.isNotEmpty(community.evidenceIds())) {
                communityEvidenceIds.addAll(community.evidenceIds());
            }
        }
        communityEvidenceIds.removeIf(Objects::isNull);
        communityEvidenceIds.removeAll(evidenceMap.keySet());
        if (!communityEvidenceIds.isEmpty()) {
            for (SuperAgentKgEvidence evidence : listEvidencesByIds(documentIds, taskIds, communityEvidenceIds)) {
                if (evidence != null && evidence.getId() != null) {
                    evidenceMap.put(evidence.getId(), evidence);
                }
            }
        }
        return new CommunityRankContext(
            index.distinctRelationGroups(),
            evidenceMap,
            communityTopicContext(index, queryProfile),
            queryProfile,
            new LinkedHashMap<>()
        );
    }

    private CommunityRankProfile communityRankProfile(CrossDocumentCommunity community,
                                                      CommunityRankContext context) {
        if (community == null || context == null) {
            return CommunityRankProfile.empty();
        }
        String key = communityRankKey(community);
        if (StrUtil.isBlank(key)) {
            return rankCrossDocumentCommunity(
                community,
                context.relationGroupByKey(),
                context.evidenceMap(),
                context.topicContext(),
                context.queryProfile()
            );
        }
        return context.rankProfileByCommunityKey().computeIfAbsent(key, ignored -> rankCrossDocumentCommunity(
            community,
            context.relationGroupByKey(),
            context.evidenceMap(),
            context.topicContext(),
            context.queryProfile()
        ));
    }

    private String communityRankKey(CrossDocumentCommunity community) {
        if (community == null) {
            return "";
        }
        if (StrUtil.isNotBlank(community.key())) {
            return community.key();
        }
        return community.id() == null ? "" : String.valueOf(community.id());
    }

    private Map<Long, List<RelationGroup>> relationGroupsByEvidenceId(GraphRagCrossDocumentIndex index) {
        if (index == null || !index.hasRelationGroups()) {
            return Map.of();
        }
        Map<Long, List<RelationGroup>> result = new LinkedHashMap<>();
        for (RelationGroup group : index.distinctRelationGroups().values()) {
            if (group == null || CollUtil.isEmpty(group.evidenceIds())) {
                continue;
            }
            for (Long evidenceId : group.evidenceIds()) {
                if (evidenceId != null) {
                    result.computeIfAbsent(evidenceId, ignored -> new ArrayList<>()).add(group);
                }
            }
        }
        return result;
    }

    private RepresentativeCommunityEvidence selectRepresentativeCommunityEvidence(CrossDocumentCommunity community,
                                                                                 Map<Long, SuperAgentKgEvidence> evidenceMap,
                                                                                 Map<Long, List<RelationGroup>> relationGroupsByEvidenceId,
                                                                                 List<String> terms,
                                                                                 QueryProfile queryProfile) {
        if (community == null || CollUtil.isEmpty(community.evidenceIds()) || evidenceMap == null || evidenceMap.isEmpty()) {
            return null;
        }
        Set<String> communityRelationGroupKeys = community.relationGroupKeys() == null
            ? Set.of()
            : community.relationGroupKeys();
        List<RepresentativeCommunityEvidence> candidates = new ArrayList<>();
        for (Long evidenceId : community.evidenceIds()) {
            SuperAgentKgEvidence evidence = evidenceMap.get(evidenceId);
            if (evidence == null || !isGraphEvidenceUsable(evidence)) {
                continue;
            }
            List<RelationGroup> relationGroups = relationGroupsByEvidenceId == null
                ? List.of()
                : relationGroupsByEvidenceId.getOrDefault(evidence.getId(), List.of()).stream()
                    .filter(group -> group != null
                        && (communityRelationGroupKeys.isEmpty() || communityRelationGroupKeys.contains(group.key())))
                    .toList();
            if (relationGroups.isEmpty()) {
                candidates.add(new RepresentativeCommunityEvidence(
                    evidence,
                    null,
                    representativeCommunityEvidenceScore(evidence, null, terms, queryProfile)
                ));
                continue;
            }
            for (RelationGroup relationGroup : relationGroups) {
                candidates.add(new RepresentativeCommunityEvidence(
                    evidence,
                    relationGroup,
                    representativeCommunityEvidenceScore(evidence, relationGroup, terms, queryProfile)
                ));
            }
        }
        return candidates.stream()
            .max(Comparator.comparingDouble(RepresentativeCommunityEvidence::score))
            .orElse(null);
    }

    private double representativeCommunityEvidenceScore(SuperAgentKgEvidence evidence,
                                                        RelationGroup relationGroup,
                                                        List<String> terms,
                                                        QueryProfile queryProfile) {
        if (evidence == null) {
            return 0D;
        }
        double score = 0.18D + evidenceBoost(evidence.getQuoteText(), terms);
        if (relationGroup != null) {
            double qualityScore = relationGroup.qualityProfile() == null ? 0D : relationGroup.qualityProfile().score();
            score += Math.min(0.28D, qualityScore * 0.28D);
            double rankBoost = relationGroup.rankProfile() == null ? 0D : relationGroup.rankProfile().rankBoost();
            score += Math.min(0.16D, rankBoost * 0.16D);
            if (relationGroup.documentCount() > 1) {
                score += 0.08D;
            }
            if (relationGroup.evidenceCount() > 1) {
                score += Math.min(0.08D, relationGroup.evidenceCount() * 0.02D);
            }
            score += relationGroupQueryBoost(relationGroup, queryProfile);
        }
        return Math.max(0D, score);
    }

    private Map<Long, SuperAgentKgEvidence> safeEvidenceMap(List<SuperAgentKgEvidence> evidences) {
        if (CollUtil.isEmpty(evidences)) {
            return new LinkedHashMap<>();
        }
        return evidences.stream()
            .filter(evidence -> evidence != null && evidence.getId() != null)
            .collect(Collectors.toMap(SuperAgentKgEvidence::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
    }

    private List<SuperAgentKgEntity> listEntities(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgEntity> wrapper = new LambdaQueryWrapper<SuperAgentKgEntity>()
            .in(SuperAgentKgEntity::getDocumentId, documentIds)
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgEntity::getTaskId, taskIds);
        }
        return entityMapper.selectList(wrapper);
    }

    private List<SuperAgentKgRelation> listRelations(List<Long> documentIds, List<Long> taskIds, Set<Long> entityIds) {
        if (CollUtil.isEmpty(entityIds)) {
            return List.of();
        }
        LambdaQueryWrapper<SuperAgentKgRelation> wrapper = new LambdaQueryWrapper<SuperAgentKgRelation>()
            .in(SuperAgentKgRelation::getDocumentId, documentIds)
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode())
            .and(query -> query
                .in(SuperAgentKgRelation::getSourceEntityId, entityIds)
                .or()
                .in(SuperAgentKgRelation::getTargetEntityId, entityIds));
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgRelation::getTaskId, taskIds);
        }
        return relationMapper.selectList(wrapper).stream()
            .filter(relation -> relation != null
                && (entityIds.contains(relation.getSourceEntityId()) || entityIds.contains(relation.getTargetEntityId())))
            .toList();
    }

    private List<SuperAgentKgRelation> listAllRelations(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgRelation> wrapper = new LambdaQueryWrapper<SuperAgentKgRelation>()
            .in(SuperAgentKgRelation::getDocumentId, documentIds)
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgRelation::getTaskId, taskIds);
        }
        return relationMapper.selectList(wrapper);
    }

    private List<SuperAgentKgRelation> loadedOrQueryRelations(List<Long> documentIds,
                                                              List<Long> taskIds,
                                                              List<SuperAgentKgRelation> loadedRelations) {
        if (CollUtil.isNotEmpty(loadedRelations)) {
            return loadedRelations;
        }
        return listAllRelations(documentIds, taskIds);
    }

    private List<SuperAgentKgCommunity> listCommunities(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgCommunity> wrapper = new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .in(SuperAgentKgCommunity::getDocumentId, documentIds)
            .eq(SuperAgentKgCommunity::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgCommunity::getTaskId, taskIds);
        }
        return communityMapper.selectList(wrapper);
    }

    private List<SuperAgentKgEvidence> listEvidences(List<Long> documentIds,
                                                     List<Long> taskIds,
                                                     Set<Long> entityIds,
                                                     Set<Long> relationIds) {
        if (CollUtil.isEmpty(entityIds) && CollUtil.isEmpty(relationIds)) {
            return List.of();
        }
        LambdaQueryWrapper<SuperAgentKgEvidence> wrapper = new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .in(SuperAgentKgEvidence::getDocumentId, documentIds)
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgEvidence::getTaskId, taskIds);
        }
        if (CollUtil.isNotEmpty(entityIds) && CollUtil.isNotEmpty(relationIds)) {
            wrapper.and(query -> query
                .in(SuperAgentKgEvidence::getEntityId, entityIds)
                .or()
                .in(SuperAgentKgEvidence::getRelationId, relationIds));
        }
        else if (CollUtil.isNotEmpty(entityIds)) {
            wrapper.in(SuperAgentKgEvidence::getEntityId, entityIds);
        }
        else {
            wrapper.in(SuperAgentKgEvidence::getRelationId, relationIds);
        }
        return evidenceMapper.selectList(wrapper);
    }

    private List<SuperAgentKgEvidence> listEvidencesByIds(List<Long> documentIds,
                                                          List<Long> taskIds,
                                                          Set<Long> evidenceIds) {
        if (CollUtil.isEmpty(evidenceIds)) {
            return List.of();
        }
        LambdaQueryWrapper<SuperAgentKgEvidence> wrapper = new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .in(SuperAgentKgEvidence::getDocumentId, documentIds)
            .in(SuperAgentKgEvidence::getId, evidenceIds)
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgEvidence::getTaskId, taskIds);
        }
        return evidenceMapper.selectList(wrapper);
    }

    private GraphRagSearchResult toResult(SuperAgentKgEvidence evidence,
                                          Map<Long, SuperAgentKgEntity> entityMap,
                                          Map<Long, SuperAgentKgRelation> relationMap,
                                          Map<Long, Integer> relationHopMap,
                                          Map<Long, RelationTrace> relationTraceMap,
                                          Map<Long, Double> seedScoreMap,
                                          Map<Long, Double> relationSeedScoreMap,
                                          GraphRagCrossDocumentIndex crossDocumentIndex,
                                          CommunityRankContext communityRankContext,
                                          List<String> terms,
                                          QueryProfile queryProfile) {
        if (evidence.getRelationId() != null) {
            SuperAgentKgRelation relation = relationMap.get(evidence.getRelationId());
            if (relation == null) {
                return null;
            }
            SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
            SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
            if (source == null || target == null) {
                return null;
            }
            if (!isGraphSearchEntityUsable(source) || !isGraphSearchEntityUsable(target)) {
                return null;
            }
            boolean sourceSeed = seedScoreMap.containsKey(source.getId());
            boolean targetSeed = seedScoreMap.containsKey(target.getId());
            SuperAgentKgEntity seed = sourceSeed || !targetSeed ? source : target;
            SuperAgentKgEntity related = sourceSeed || !targetSeed ? target : source;
            RelationTrace relationTrace = relationTraceMap.get(relation.getId());
            SuperAgentKgEntity tracedSeed = relationTrace == null || relationTrace.seedEntityId() == null
                ? null
                : entityMap.get(relationTrace.seedEntityId());
            if (tracedSeed != null
                && (Objects.equals(tracedSeed.getId(), source.getId()) || Objects.equals(tracedSeed.getId(), target.getId()))) {
                seed = tracedSeed;
                if (Objects.equals(tracedSeed.getId(), source.getId())) {
                    related = target;
                }
                else if (Objects.equals(tracedSeed.getId(), target.getId())) {
                    related = source;
                }
            }
            double seedScore = Math.max(
                seedScoreMap.getOrDefault(source.getId(), 0.25D),
                seedScoreMap.getOrDefault(target.getId(), 0.25D)
            );
            seedScore = Math.max(seedScore, seedScoreMap.getOrDefault(seed.getId(), 0.25D));
            seedScore = Math.max(seedScore, relationSeedScoreMap.getOrDefault(relation.getId(), 0D));
            int hopCount = relationHopMap.getOrDefault(relation.getId(), 1);
            seedScore = Math.max(seedScore, nHopSeedScore(relationTrace, seedScoreMap, hopCount));
            double hopPenalty = hopPenalty(relation, source, target, queryProfile, hopCount);
            double rankBoost = relationRankBoost(relation, source, target);
            RelationGroup relationGroup = crossDocumentIndex == null ? null : crossDocumentIndex.relationGroupOf(relation.getId());
            double groupBoost = relationGroupBoost(relationGroup);
            double groupQueryBoost = relationGroupQueryBoost(relationGroup, queryProfile);
            double score = seedScore
                + relationWeight(relation.getWeight())
                + graphRankScore(rankBoost)
                + evidenceBoost(evidence.getQuoteText(), terms)
                + groupBoost
                + groupQueryBoost
                + answerTypeRelationBoost(relation, source, target, queryProfile, hopCount)
                - hopPenalty;
            String graphPath = graphPath(relation, source, target, relationTrace, hopCount);
            GraphRagSearchResult.GraphRagSearchResultBuilder builder = baseResult(evidence)
                .entityId(seed.getId())
                .entityName(seed.getName())
                .relationId(relation.getId())
                .relationType(relation.getRelationType())
                .relatedEntityId(related.getId())
                .relatedEntityName(related.getName())
                .graphPath(graphPath)
                .hopCount(hopCount)
                .nHopSeedEntityId(relationTrace == null ? null : relationTrace.seedEntityId())
                .nHopSeedEntityName(relationTrace == null ? "" : relationTrace.seedEntityName())
                .nHopPath(relationTrace == null ? "" : relationTrace.path())
                .rankBoost(rankBoost)
                .score(score);
            builder = withQueryProfile(builder, queryProfile);
            builder = withCanonicalEntity(builder, seed, crossDocumentIndex);
            CrossDocumentCommunity crossDocumentCommunity = relationGroup == null
                ? null
                : crossDocumentIndex.communityOfRelationGroup(relationGroup.key());
            builder = withRelationGroup(builder, relationGroup);
            builder = withCrossDocumentCommunity(builder, crossDocumentCommunity);
            builder = withCommunityRankProfile(builder, communityRankProfile(crossDocumentCommunity, communityRankContext));
            return builder.build();
        }

        if (evidence.getEntityId() != null) {
            SuperAgentKgEntity entity = entityMap.get(evidence.getEntityId());
            if (entity == null) {
                return null;
            }
            if (!isGraphSearchEntityUsable(entity)) {
                return null;
            }
            double rankBoost = entityRankBoost(entity);
            double score = seedScoreMap.getOrDefault(entity.getId(), 0.35D)
                + graphRankScore(rankBoost)
                + evidenceBoost(evidence.getQuoteText(), terms);
            GraphRagSearchResult.GraphRagSearchResultBuilder builder = baseResult(evidence)
                .entityId(entity.getId())
                .entityName(entity.getName())
                .graphPath(entity.getName())
                .hopCount(0)
                .rankBoost(rankBoost)
                .score(score);
            builder = withQueryProfile(builder, queryProfile);
            return withCanonicalEntity(builder, entity, crossDocumentIndex).build();
        }
        return null;
    }

    private double nHopSeedScore(RelationTrace relationTrace,
                                 Map<Long, Double> seedScoreMap,
                                 int hopCount) {
        if (relationTrace == null || relationTrace.seedEntityId() == null || seedScoreMap == null || seedScoreMap.isEmpty()) {
            return 0D;
        }
        double seedScore = seedScoreMap.getOrDefault(relationTrace.seedEntityId(), 0D);
        if (seedScore <= 0D) {
            return 0D;
        }
        if (hopCount <= 1) {
            return seedScore;
        }
        return seedScore * Math.max(0.45D, 1.0D - (hopCount - 1) * 0.15D);
    }

    private double hopPenalty(SuperAgentKgRelation relation,
                              SuperAgentKgEntity source,
                              SuperAgentKgEntity target,
                              QueryProfile queryProfile,
                              int hopCount) {
        if (hopCount <= 1) {
            return 0D;
        }
        String relationType = normalizedRelationType(relation);
        double penalty = 0.18D + Math.max(0, hopCount - 2) * 0.08D;
        if (hasAnswerTypeEndpointMatch(source, target, queryProfile)
            && relationMatchesQueryProfile(relation, relationType, queryProfile)) {
            penalty -= 0.14D;
        }
        return Math.max(0.03D, penalty);
    }

    private double answerTypeRelationBoost(SuperAgentKgRelation relation,
                                           SuperAgentKgEntity source,
                                           SuperAgentKgEntity target,
                                           QueryProfile queryProfile,
                                           int hopCount) {
        if (!hasAnswerTypeEndpointMatch(source, target, queryProfile)) {
            return 0D;
        }
        String relationType = normalizedRelationType(relation);
        boolean plannedRelation = relationMatchesQueryProfile(relation, relationType, queryProfile);
        double boost = hopCount > 1 ? 0.24D : 0.18D;
        if (plannedRelation) {
            boost += 0.16D;
        }
        return boost;
    }

    private boolean relationMatchesQueryProfile(SuperAgentKgRelation relation,
                                                String relationType,
                                                QueryProfile queryProfile) {
        if (relation == null || queryProfile == null) {
            return false;
        }
        return queryProfile.relationTypes().contains(relationType)
            || queryProfile.relationIds().contains(relation.getId());
    }

    private boolean hasAnswerTypeEndpointMatch(SuperAgentKgEntity source,
                                               SuperAgentKgEntity target,
                                               QueryProfile queryProfile) {
        Set<String> answerTypes = answerTypes(queryProfile);
        if (answerTypes.isEmpty()) {
            return false;
        }
        return answerTypes.contains(canonicalEntityType(source))
            || answerTypes.contains(canonicalEntityType(target));
    }

    private Set<String> answerTypes(QueryProfile queryProfile) {
        if (queryProfile == null) {
            return Set.of();
        }
        if (CollUtil.isNotEmpty(queryProfile.answerTypeKeywords())) {
            return queryProfile.answerTypeKeywords();
        }
        return queryProfile.entityTypes();
    }

    private String normalizedRelationType(SuperAgentKgRelation relation) {
        return StrUtil.blankToDefault(relation == null ? null : relation.getRelationType(), "ASSOCIATED_WITH")
            .trim()
            .toUpperCase(Locale.ROOT);
    }

    private String graphPath(SuperAgentKgRelation relation,
                             SuperAgentKgEntity source,
                             SuperAgentKgEntity target,
                             RelationTrace relationTrace,
                             int hopCount) {
        String prefix = hopCount <= 0 ? "关系匹配：" : hopCount <= 1 ? "一跳：" : "二跳：";
        if (relationTrace != null && StrUtil.isNotBlank(relationTrace.path())) {
            return prefix + relationTrace.path();
        }
        return prefix + source.getName() + " --" + relationPresentationLabel(relation) + "--> " + target.getName();
    }

    private double relationGroupBoost(RelationGroup group) {
        return crossDocumentIndexSupport.relationGroupBoost(group);
    }

    private double relationGroupQueryBoost(RelationGroup group, QueryProfile queryProfile) {
        if (group == null || queryProfile == null) {
            return 0D;
        }
        double boost = 0D;
        String relationType = StrUtil.blankToDefault(group.relationType(), "ASSOCIATED_WITH")
            .trim()
            .toUpperCase(Locale.ROOT);
        if (queryProfile.relationTypes().contains(relationType)) {
            boost += 0.16D;
        }
        if (CollUtil.isNotEmpty(group.relationIds())
            && group.relationIds().stream().anyMatch(queryProfile.relationIds()::contains)) {
            boost += 0.20D;
        }
        if (relationGroupEndpointMatchesAnswerType(group, queryProfile)) {
            boost += 0.10D;
        }
        if (queryProfile.communityQuestion()) {
            boost += Math.min(0.12D, group.documentCount() * 0.03D + group.evidenceCount() * 0.01D);
        }
        return Math.min(0.36D, boost);
    }

    private boolean relationGroupEndpointMatchesAnswerType(RelationGroup group, QueryProfile queryProfile) {
        Set<String> answerTypes = answerTypes(queryProfile);
        if (group == null || answerTypes.isEmpty()) {
            return false;
        }
        return relationGroupEndpointMatchesAnswerType(group.sourceGroupKey(), answerTypes)
            || relationGroupEndpointMatchesAnswerType(group.targetGroupKey(), answerTypes);
    }

    private boolean relationGroupEndpointMatchesAnswerType(String groupKey, Set<String> answerTypes) {
        if (StrUtil.isBlank(groupKey) || CollUtil.isEmpty(answerTypes)) {
            return false;
        }
        String normalizedGroupKey = groupKey.trim().toUpperCase(Locale.ROOT);
        for (String answerType : answerTypes) {
            if (StrUtil.isNotBlank(answerType) && normalizedGroupKey.startsWith(answerType + ":")) {
                return true;
            }
        }
        return false;
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withRelationGroup(
        GraphRagSearchResult.GraphRagSearchResultBuilder builder,
        RelationGroup group
    ) {
        if (group == null) {
            return builder;
        }
        return builder
            .relationGroupKey(group.key())
            .relationGroupRelationCount(group.relationCount())
            .relationGroupEvidenceCount(group.evidenceCount())
            .relationGroupDocumentCount(group.documentCount())
            .kgQualityScore(group.qualityProfile() == null ? null : group.qualityProfile().score())
            .kgQualityReasons(group.qualityProfile() == null ? "" : String.join(",", group.qualityProfile().qualityReasons()))
            .kgNoiseReasons(group.qualityProfile() == null ? "" : String.join(",", group.qualityProfile().noiseReasons()))
            .kgPagerank(group.rankProfile() == null ? null : group.rankProfile().pagerank())
            .kgRankPosition(group.rankProfile() == null ? null : group.rankProfile().rankPosition())
            .kgDegree(group.rankProfile() == null ? null : group.rankProfile().degree());
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withCrossDocumentCommunity(
        GraphRagSearchResult.GraphRagSearchResultBuilder builder,
        CrossDocumentCommunity community
    ) {
        if (community == null) {
            return builder;
        }
        return builder
            .communityId(community.id())
            .communityTitle(community.title())
            .communitySummary(community.summary())
            .crossDocumentCommunityKey(community.key())
            .crossDocumentCommunityEntityCount(community.entityCount())
            .crossDocumentCommunityRelationGroupCount(community.relationGroupCount())
            .crossDocumentCommunityEvidenceCount(community.evidenceCount())
            .crossDocumentCommunityDocumentCount(community.documentCount())
            .kgQualityScore(community.qualityProfile() == null ? null : community.qualityProfile().score())
            .kgQualityReasons(community.qualityProfile() == null ? "" : String.join(",", community.qualityProfile().qualityReasons()))
            .kgNoiseReasons(community.qualityProfile() == null ? "" : String.join(",", community.qualityProfile().noiseReasons()))
            .kgPagerank(community.rankProfile() == null ? null : community.rankProfile().pagerank())
            .kgRankPosition(community.rankProfile() == null ? null : community.rankProfile().rankPosition())
            .kgDegree(community.rankProfile() == null ? null : community.rankProfile().degree());
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withCommunityRankProfile(
        GraphRagSearchResult.GraphRagSearchResultBuilder builder,
        CommunityRankProfile rankProfile
    ) {
        if (rankProfile == null || rankProfile.score() <= 0D) {
            return builder;
        }
        return builder
            .kgCommunityRankScore(rankProfile.score())
            .kgCommunityRankReasons(String.join(",", rankProfile.reasons()));
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withQueryProfile(
        GraphRagSearchResult.GraphRagSearchResultBuilder builder,
        QueryProfile queryProfile
    ) {
        if (queryProfile == null) {
            return builder;
        }
        return builder
            .queryPlanSource(StrUtil.blankToDefault(queryProfile.sourceText(), ""))
            .queryPlanAnswerTypes(String.join(",", queryProfile.answerTypeKeywords()))
            .queryPlanEntities(String.join(",", queryProfile.entitiesFromQuery()));
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withCanonicalEntity(GraphRagSearchResult.GraphRagSearchResultBuilder builder,
                                                                                SuperAgentKgEntity entity,
                                                                                GraphRagCrossDocumentIndex crossDocumentIndex) {
        CanonicalEntityGroup group = crossDocumentIndex == null ? null : crossDocumentIndex.canonicalGroupOf(entity == null ? null : entity.getId());
        if (group == null || group.entityIds().size() <= 1) {
            return builder;
        }
        return builder
            .canonicalEntityKey(group.key())
            .canonicalEntityName(group.name())
            .canonicalEntityCount(group.entityIds().size())
            .canonicalDocumentCount(group.documentIds().size())
            .kgQualityScore(group.qualityProfile() == null ? null : group.qualityProfile().score())
            .kgQualityReasons(group.qualityProfile() == null ? "" : String.join(",", group.qualityProfile().qualityReasons()))
            .kgNoiseReasons(group.qualityProfile() == null ? "" : String.join(",", group.qualityProfile().noiseReasons()))
            .kgPagerank(group.rankProfile() == null ? null : group.rankProfile().pagerank())
            .kgRankPosition(group.rankProfile() == null ? null : group.rankProfile().rankPosition())
            .kgDegree(group.rankProfile() == null ? null : group.rankProfile().degree());
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder baseResult(SuperAgentKgEvidence evidence) {
        return GraphRagSearchResult.builder()
            .documentId(evidence.getDocumentId())
            .taskId(evidence.getTaskId())
            .evidenceId(evidence.getId())
            .chunkId(evidence.getChunkId())
            .parentBlockId(evidence.getParentBlockId())
            .quoteText(evidence.getQuoteText())
            .pageNo(evidence.getPageNo())
            .pageRange(evidence.getPageRange())
            .bboxJson(evidence.getBboxJson())
            .sectionPath(evidence.getSectionPath());
    }

    private Set<Long> relatedEntityIds(Iterable<SuperAgentKgRelation> relations) {
        Set<Long> entityIds = new LinkedHashSet<>();
        if (relations == null) {
            return entityIds;
        }
        for (SuperAgentKgRelation relation : relations) {
            if (relation == null) {
                continue;
            }
            entityIds.add(relation.getSourceEntityId());
            entityIds.add(relation.getTargetEntityId());
        }
        entityIds.removeIf(Objects::isNull);
        return entityIds;
    }

    private boolean isDistinctiveCanonicalVariant(String normalized) {
        return crossDocumentIndexSupport.isDistinctiveCanonicalVariant(normalized);
    }

    private String canonicalEntityType(SuperAgentKgEntity entity) {
        return crossDocumentIndexSupport.canonicalEntityType(entity);
    }

    private String normalizeCanonicalVariant(String value) {
        return crossDocumentIndexSupport.normalizeCanonicalVariant(value);
    }

    private Map<Long, Double> expandCanonicalSeedScores(Map<Long, Double> seedScoreMap,
                                                        GraphRagCrossDocumentIndex crossDocumentIndex) {
        if (seedScoreMap.isEmpty() || crossDocumentIndex == null || !crossDocumentIndex.hasCanonicalGroups()) {
            return seedScoreMap;
        }
        Map<Long, Double> expanded = new LinkedHashMap<>(seedScoreMap);
        for (Map.Entry<Long, Double> entry : seedScoreMap.entrySet()) {
            CanonicalEntityGroup group = crossDocumentIndex.canonicalGroupOf(entry.getKey());
            if (group == null || group.entityIds().size() <= 1) {
                continue;
            }
            double expandedScore = Math.max(0.1D, entry.getValue() * 0.92D);
            for (Long entityId : group.entityIds()) {
                expanded.merge(entityId, expandedScore, Math::max);
            }
        }
        return expanded;
    }

    private Set<Long> expandCanonicalEntityIds(Set<Long> entityIds, GraphRagCrossDocumentIndex crossDocumentIndex) {
        if (CollUtil.isEmpty(entityIds) || crossDocumentIndex == null || !crossDocumentIndex.hasCanonicalGroups()) {
            return entityIds;
        }
        Set<Long> expanded = new LinkedHashSet<>(entityIds);
        for (Long entityId : entityIds) {
            CanonicalEntityGroup group = crossDocumentIndex.canonicalGroupOf(entityId);
            if (group != null && group.entityIds().size() > 1) {
                expanded.addAll(group.entityIds());
            }
        }
        return expanded;
    }

    private Map<Long, EntityTrace> expandCanonicalSeedTraces(List<ScoredEntity> seedEntities,
                                                             GraphRagCrossDocumentIndex crossDocumentIndex) {
        Map<Long, EntityTrace> traces = new LinkedHashMap<>();
        if (CollUtil.isEmpty(seedEntities)) {
            return traces;
        }
        for (ScoredEntity scoredEntity : seedEntities) {
            SuperAgentKgEntity entity = scoredEntity.entity();
            if (entity == null || entity.getId() == null) {
                continue;
            }
            EntityTrace trace = new EntityTrace(entity.getId(), entity.getName(), entity.getName());
            traces.put(entity.getId(), trace);
            CanonicalEntityGroup group = crossDocumentIndex == null ? null : crossDocumentIndex.canonicalGroupOf(entity.getId());
            if (group == null || group.entityIds().size() <= 1) {
                continue;
            }
            for (Long entityId : group.entityIds()) {
                traces.putIfAbsent(entityId, trace);
            }
        }
        return traces;
    }

    private Map<Long, EntityTrace> oneHopEntityTraces(Collection<SuperAgentKgRelation> relations,
                                                      Map<Long, SuperAgentKgEntity> entityMap,
                                                      Map<Long, EntityTrace> seedTraceMap) {
        Map<Long, EntityTrace> traces = new LinkedHashMap<>();
        if (CollUtil.isEmpty(relations) || seedTraceMap.isEmpty()) {
            return traces;
        }
        for (SuperAgentKgRelation relation : relations) {
            SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
            SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
            if (source == null || target == null) {
                continue;
            }
            EntityTrace sourceSeedTrace = seedTraceMap.get(source.getId());
            EntityTrace targetSeedTrace = seedTraceMap.get(target.getId());
            if (sourceSeedTrace != null && targetSeedTrace == null) {
                traces.putIfAbsent(target.getId(), appendTrace(sourceSeedTrace, relation, source, target));
            }
            if (targetSeedTrace != null && sourceSeedTrace == null) {
                traces.putIfAbsent(source.getId(), appendTrace(targetSeedTrace, relation, target, source));
            }
        }
        return traces;
    }

    private void mergeExpandedRelationTrace(Map<Long, SuperAgentKgRelation> relationMap,
                                            Map<Long, Integer> relationHopMap,
                                            Map<Long, RelationTrace> relationTraceMap,
                                            SuperAgentKgRelation relation,
                                            int hopCount,
                                            RelationTrace trace,
                                            QueryProfile queryProfile) {
        if (relation == null || relation.getId() == null) {
            return;
        }
        relationMap.put(relation.getId(), relation);
        Integer existingHop = relationHopMap.get(relation.getId());
        RelationTrace existingTrace = relationTraceMap.get(relation.getId());
        if (shouldReplaceRelationTrace(relation, existingHop, existingTrace, hopCount, trace, queryProfile)) {
            relationHopMap.put(relation.getId(), hopCount);
            if (trace != null) {
                relationTraceMap.put(relation.getId(), trace);
            }
        }
        else {
            relationHopMap.putIfAbsent(relation.getId(), hopCount);
            if (trace != null) {
                relationTraceMap.putIfAbsent(relation.getId(), trace);
            }
        }
    }

    private boolean shouldReplaceRelationTrace(SuperAgentKgRelation relation,
                                               Integer existingHop,
                                               RelationTrace existingTrace,
                                               int candidateHop,
                                               RelationTrace candidateTrace,
                                               QueryProfile queryProfile) {
        if (relation == null || relation.getId() == null || candidateTrace == null || StrUtil.isBlank(candidateTrace.path())) {
            return false;
        }
        boolean candidateHasPath = candidateTrace.path().contains("--");
        boolean existingHasPath = existingTrace != null && StrUtil.isNotBlank(existingTrace.path())
            && existingTrace.path().contains("--");
        if (!candidateHasPath || candidateHop <= 1) {
            return false;
        }
        String relationType = normalizedRelationType(relation);
        boolean advisorSeededRelation = queryProfile != null
            && (queryProfile.relationTypes().contains(relationType)
            || queryProfile.relationIds().contains(relation.getId()));
        if (!advisorSeededRelation) {
            return false;
        }
        boolean candidateSeedMatchesQuery = relationTraceSeedMatchesQuery(candidateTrace, queryProfile);
        boolean existingSeedMatchesQuery = relationTraceSeedMatchesQuery(existingTrace, queryProfile);
        if (candidateSeedMatchesQuery && !existingSeedMatchesQuery) {
            return true;
        }
        if (existingHasPath) {
            return false;
        }
        return existingHop == null || existingHop <= 0 || candidateHop > existingHop;
    }

    private boolean relationTraceSeedMatchesQuery(RelationTrace trace, QueryProfile queryProfile) {
        if (trace == null || queryProfile == null) {
            return false;
        }
        if (trace.seedEntityId() != null && queryProfile.entityIds().contains(trace.seedEntityId())) {
            return true;
        }
        String normalizedSeedName = normalize(trace.seedEntityName());
        if (StrUtil.isBlank(normalizedSeedName)) {
            return false;
        }
        if (queryProfile.entityNames().contains(normalizedSeedName)
            || queryProfile.entitiesFromQuery().contains(normalizedSeedName)) {
            return true;
        }
        for (String queryEntity : queryProfile.entitiesFromQuery()) {
            String normalizedQueryEntity = normalize(queryEntity);
            if (normalizedQueryEntity.length() >= 2
                && (normalizedSeedName.contains(normalizedQueryEntity)
                || normalizedQueryEntity.contains(normalizedSeedName))) {
                return true;
            }
        }
        return false;
    }

    private RelationTrace relationTrace(SuperAgentKgRelation relation,
                                        Map<Long, SuperAgentKgEntity> entityMap,
                                        Map<Long, EntityTrace> traceMap,
                                        int hopCount,
                                        String sourceText) {
        if (relation == null || traceMap == null || traceMap.isEmpty()) {
            return null;
        }
        SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
        SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
        if (source == null || target == null) {
            return null;
        }
        EntityTrace sourceTrace = traceMap.get(source.getId());
        if (sourceTrace != null) {
            EntityTrace fullTrace = appendTrace(sourceTrace, relation, source, target);
            return new RelationTrace(sourceTrace.seedEntityId(), sourceTrace.seedEntityName(), fullTrace.path(), sourceText);
        }
        EntityTrace targetTrace = traceMap.get(target.getId());
        if (targetTrace != null) {
            EntityTrace fullTrace = appendTrace(targetTrace, relation, target, source);
            return new RelationTrace(targetTrace.seedEntityId(), targetTrace.seedEntityName(), fullTrace.path(), sourceText);
        }
        return null;
    }

    private EntityTrace appendTrace(EntityTrace trace,
                                    SuperAgentKgRelation relation,
                                    SuperAgentKgEntity from,
                                    SuperAgentKgEntity to) {
        if (trace == null || relation == null || from == null || to == null) {
            return trace;
        }
        String currentPath = StrUtil.blankToDefault(trace.path(), from.getName());
        String relationType = relationPresentationLabel(relation);
        String relationSegment;
        if (Objects.equals(relation.getSourceEntityId(), from.getId())
            && Objects.equals(relation.getTargetEntityId(), to.getId())) {
            relationSegment = " --" + relationType + "--> ";
        }
        else if (Objects.equals(relation.getTargetEntityId(), from.getId())
            && Objects.equals(relation.getSourceEntityId(), to.getId())) {
            relationSegment = " <--" + relationType + "-- ";
        }
        else {
            relationSegment = " --" + relationType + "-- ";
        }
        return new EntityTrace(
            trace.seedEntityId(),
            trace.seedEntityName(),
            currentPath + relationSegment + to.getName()
        );
    }

    private String relationPresentationLabel(SuperAgentKgRelation relation) {
        return GraphRagRelationAuthority.presentationLabel(readMap(relation == null ? null : relation.getMetadataJson()));
    }

    private double scoreEntity(SuperAgentKgEntity entity,
                               String normalizedQuestion,
                               List<String> terms,
                               QueryProfile queryProfile) {
        String normalizedName = normalize(StrUtil.blankToDefault(entity.getNormalizedName(), entity.getName()));
        String name = normalize(entity.getName());
        if (normalizedName.isBlank() && name.isBlank()) {
            return 0D;
        }

        double score = 0D;
        if (StrUtil.isNotBlank(normalizedName) && normalizedQuestion.contains(normalizedName)) {
            score += 1.0D;
        }
        if (StrUtil.isNotBlank(name) && normalizedQuestion.contains(name)) {
            score += 0.8D;
        }
        for (String alias : entityAliases(entity)) {
            String normalizedAlias = normalize(alias);
            if (normalizedAlias.length() >= 2 && normalizedQuestion.contains(normalizedAlias)) {
                score += 0.7D;
            }
        }
        String description = normalize(entity.getDescription());
        if (StrUtil.isNotBlank(description)) {
            for (String term : terms) {
                String normalizedTerm = normalize(term);
                if (normalizedTerm.length() >= 2 && description.contains(normalizedTerm)) {
                    score += 0.12D;
                }
            }
        }
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() < 2) {
                continue;
            }
            if ((StrUtil.isNotBlank(name) && name.contains(normalizedTerm))
                || (StrUtil.isNotBlank(normalizedName) && normalizedName.contains(normalizedTerm))) {
                score += 0.35D;
            }
            else if ((StrUtil.isNotBlank(name) && normalizedTerm.contains(name))
                || (StrUtil.isNotBlank(normalizedName) && normalizedTerm.contains(normalizedName))) {
                score += 0.25D;
            }
        }
        boolean answerTypeMatched = queryProfile.entityTypes()
            .contains(StrUtil.blankToDefault(entity.getEntityType(), "").toUpperCase(Locale.ROOT));
        if (queryProfile.entityIds().contains(entity.getId())) {
            score += 0.95D;
        }
        if (matchesAdvisorEntityName(entity, queryProfile.entityNames())) {
            score += 0.62D;
        }
        if (matchesQueryEntityPhrase(entity, queryProfile.entitiesFromQuery())) {
            score += 0.58D;
        }
        if (score > 0D && answerTypeMatched) {
            score += 0.22D;
        }
        if (score <= 0D) {
            return 0D;
        }
        if (isLowDistinctivenessEntitySeed(entity)
            && !queryProfile.entityIds().contains(entity.getId())
            && !matchesAdvisorEntityName(entity, queryProfile.entityNames())
            && !matchesQueryEntityPhrase(entity, queryProfile.entitiesFromQuery())) {
            score *= 0.62D;
        }
        score += Math.min(0.18D, metadataConfidence(entity.getMetadataJson()) * 0.18D);
        score += graphRankScore(entityRankBoost(entity)) * 0.45D;
        return Math.min(score, 2.2D);
    }

    private boolean isLowDistinctivenessEntitySeed(SuperAgentKgEntity entity) {
        if (entity == null) {
            return true;
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(normalizeCanonicalVariant(entity.getName()));
        variants.add(normalizeCanonicalVariant(StrUtil.blankToDefault(entity.getNormalizedName(), entity.getName())));
        for (String alias : entityAliases(entity)) {
            variants.add(normalizeCanonicalVariant(alias));
        }
        return variants.stream()
            .filter(StrUtil::isNotBlank)
            .noneMatch(this::isDistinctiveCanonicalVariant);
    }

    private boolean isGraphSearchEntityUsable(SuperAgentKgEntity entity) {
        return crossDocumentIndexSupport.isGraphSearchEntityUsable(entity);
    }

    private Set<String> entityCandidateSources(SuperAgentKgEntity entity) {
        return crossDocumentIndexSupport.entityCandidateSources(entity);
    }

    private boolean isGraphEvidenceUsable(SuperAgentKgEvidence evidence) {
        return crossDocumentIndexSupport.isGraphEvidenceUsable(evidence);
    }

    private double scoreRelation(SuperAgentKgRelation relation,
                                 Map<Long, SuperAgentKgEntity> entityMap,
                                 String normalizedQuestion,
                                 List<String> terms,
                                 QueryProfile queryProfile) {
        SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
        SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
        if (source == null || target == null) {
            return 0D;
        }
        if (!isGraphSearchEntityUsable(source) || !isGraphSearchEntityUsable(target)) {
            return 0D;
        }
        String relationType = StrUtil.blankToDefault(relation.getRelationType(), "ASSOCIATED_WITH").toUpperCase(Locale.ROOT);
        String description = normalize(relation.getDescription());
        String sourceText = searchableEntityText(source);
        String targetText = searchableEntityText(target);
        String relationText = normalize(relationType + " " + StrUtil.blankToDefault(relation.getDescription(), "") + " " + sourceText + " " + targetText);

        double score = 0D;
        if (queryProfile.relationTypes().contains(relationType)) {
            score += 0.82D;
        }
        if (queryProfile.relationIds().contains(relation.getId())) {
            score += 0.95D;
        }
        if (matchesQueryEntityPhrase(source, queryProfile.entitiesFromQuery())
            || matchesQueryEntityPhrase(target, queryProfile.entitiesFromQuery())) {
            score += 0.34D;
        }
        if (StrUtil.isNotBlank(description) && normalizedQuestion.contains(description)) {
            score += 0.35D;
        }
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() < 2) {
                continue;
            }
            if (sourceText.contains(normalizedTerm) || targetText.contains(normalizedTerm)) {
                score += 0.28D;
            }
            if (relationText.contains(normalizedTerm)) {
                score += 0.16D;
            }
        }
        if (!queryProfile.entityTypes().isEmpty()) {
            String sourceType = StrUtil.blankToDefault(source.getEntityType(), "").toUpperCase(Locale.ROOT);
            String targetType = StrUtil.blankToDefault(target.getEntityType(), "").toUpperCase(Locale.ROOT);
            if (queryProfile.entityTypes().contains(sourceType) || queryProfile.entityTypes().contains(targetType)) {
                score += 0.14D;
            }
        }
        if (score <= 0D) {
            return 0D;
        }
        if (queryProfile.relationQuestion()) {
            score += 0.12D;
        }
        score += relationWeight(relation.getWeight()) * 0.55D;
        score += graphRankScore(relationRankBoost(relation, source, target)) * 0.40D;
        return Math.min(score, 2.0D);
    }

    private double relationWeight(BigDecimal weight) {
        if (weight == null) {
            return 0.35D;
        }
        return Math.min(0.6D, Math.max(0D, weight.doubleValue()) * 0.5D);
    }

    private double graphRankScore(double rankBoost) {
        return Math.min(0.22D, Math.max(0D, rankBoost) * 0.22D);
    }

    private double entityRankBoost(SuperAgentKgEntity entity) {
        return crossDocumentIndexSupport.entityRankBoost(entity);
    }

    private double relationRankBoost(SuperAgentKgRelation relation,
                                     SuperAgentKgEntity source,
                                     SuperAgentKgEntity target) {
        return crossDocumentIndexSupport.relationRankBoost(relation, source, target);
    }

    private double evidenceBoost(String quoteText, List<String> terms) {
        String normalizedQuote = normalize(quoteText);
        if (normalizedQuote.isBlank() || terms.isEmpty()) {
            return 0D;
        }
        double boost = 0D;
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() >= 2 && normalizedQuote.contains(normalizedTerm)) {
                boost += 0.08D;
            }
        }
        return Math.min(0.32D, boost);
    }

    private double scoreCommunity(SuperAgentKgCommunity community,
                                  String normalizedQuestion,
                                  List<String> terms,
                                  QueryProfile queryProfile) {
        String title = normalize(community.getTitle());
        String summary = normalize(community.getSummary());
        if (title.isBlank() && summary.isBlank()) {
            return 0D;
        }
        double score = 0D;
        if (queryProfile.communityIds().contains(community.getId())) {
            score += 0.92D;
        }
        if (queryProfile.communityQuestion()) {
            score += 0.12D;
        }
        if (StrUtil.isNotBlank(title) && normalizedQuestion.contains(title)) {
            score += 0.8D;
        }
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() < 2) {
                continue;
            }
            if (title.contains(normalizedTerm)) {
                score += 0.28D;
            }
            if (summary.contains(normalizedTerm)) {
                score += 0.18D;
            }
        }
        return Math.min(score, 1.2D);
    }

    private double communityRankBoost(SuperAgentKgCommunity community) {
        Map<String, Object> metadata = readMap(community == null ? null : community.getMetadataJson());
        return numberValue(metadata.get("rankBoost"), 0D);
    }

    private CommunityRankProfile rankCrossDocumentCommunity(CrossDocumentCommunity community,
                                                            Map<String, RelationGroup> relationGroupByKey,
                                                            Map<Long, SuperAgentKgEvidence> evidenceMap,
                                                            CommunityTopicContext topicContext,
                                                            QueryProfile queryProfile) {
        if (community == null) {
            return CommunityRankProfile.empty();
        }
        CommunityTopicGrounding grounding = assessCommunityTopicGrounding(
            community,
            relationGroupByKey,
            evidenceMap,
            topicContext,
            queryProfile
        );
        if (!grounding.grounded()) {
            return CommunityRankProfile.empty();
        }

        LinkedHashSet<String> reasons = new LinkedHashSet<>(grounding.reasons());
        if (queryProfile.communityQuestion()) {
            reasons.add("communityQuestion");
        }
        double score = Math.min(1.18D, grounding.score());
        double qualityScore = community.qualityProfile() == null ? 0D : community.qualityProfile().score();
        if (qualityScore > 0D) {
            score += Math.min(0.22D, qualityScore * 0.22D);
            reasons.add("communityQuality");
        }
        double reportQualityScore = community.reportProfile() == null ? 0D : community.reportProfile().qualityScore();
        if (reportQualityScore > 0D) {
            score += Math.min(0.12D, reportQualityScore * 0.12D);
            reasons.add("reportQuality");
        }
        if (community.documentCount() > 1) {
            score += 0.08D;
            reasons.add("crossDocumentCoverage");
        }
        if (community.evidenceCount() > 1) {
            score += Math.min(0.08D, community.evidenceCount() * 0.015D);
            reasons.add("evidenceCoverage");
        }
        if (community.relationGroupCount() > 1) {
            score += Math.min(0.08D, community.relationGroupCount() * 0.015D);
            reasons.add("relationGroupCoverage");
        }
        double rankBoost = community.rankProfile() == null ? 0D : community.rankProfile().rankBoost();
        double rankScore = graphRankScore(rankBoost) * 0.72D;
        if (rankScore > 0D) {
            score += rankScore;
            reasons.add("graphRank");
        }
        double pagerank = community.rankProfile() == null ? 0D : community.rankProfile().pagerank();
        if (pagerank > 0D) {
            score += Math.min(0.05D, pagerank * 0.40D);
            reasons.add("pagerank");
        }
        double penalty = communityNoisePenalty(community);
        if (penalty > 0D) {
            score -= penalty;
            reasons.add("noisePenalty");
        }
        return new CommunityRankProfile(Math.max(0D, Math.min(score, 1.85D)), List.copyOf(reasons));
    }

    private CommunityTopicContext communityTopicContext(GraphRagCrossDocumentIndex index,
                                                        QueryProfile queryProfile) {
        LinkedHashSet<String> focusTerms = new LinkedHashSet<>();
        if (queryProfile != null) {
            focusTerms.addAll(queryProfile.focusEntities());
            focusTerms.addAll(queryProfile.entityNames());
            focusTerms.addAll(queryProfile.entitiesFromQuery());
            for (Long entityId : queryProfile.entityIds()) {
                CanonicalEntityGroup group = index == null ? null : index.canonicalGroupOf(entityId);
                if (group != null) {
                    addNormalizedIfNotBlank(focusTerms, group.name());
                    addNormalizedIfNotBlank(focusTerms, group.key());
                }
            }
        }
        LinkedHashSet<String> genericIntentTerms = queryProfile == null
            ? new LinkedHashSet<>()
            : queryProfile.genericIntentTerms().stream()
            .map(this::normalize)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> highCoverageTerms = highCoverageCommunityTerms(index, focusTerms);
        LinkedHashSet<String> normalizedFocusTerms = focusTerms.stream()
            .map(this::normalize)
            .filter(term -> !genericIntentTerms.contains(term))
            .filter(term -> !highCoverageTerms.contains(term))
            .filter(this::isCommunityFocusTerm)
            .limit(20)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return new CommunityTopicContext(normalizedFocusTerms, genericIntentTerms);
    }

    private Set<String> highCoverageCommunityTerms(GraphRagCrossDocumentIndex index, Collection<String> rawTerms) {
        if (index == null || !index.hasCommunities() || CollUtil.isEmpty(rawTerms) || index.communityByKey().size() < 2) {
            return Set.of();
        }
        List<CrossDocumentCommunity> communities = new ArrayList<>(index.communityByKey().values());
        int threshold = Math.max(2, (int) Math.ceil(communities.size() * 0.60D));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String rawTerm : rawTerms) {
            String term = normalize(rawTerm);
            if (!isCommunityFocusTerm(term)) {
                continue;
            }
            long coverage = communities.stream()
                .filter(community -> communityTextContains(community, term))
                .count();
            if (coverage >= threshold) {
                result.add(term);
            }
        }
        return result;
    }

    private boolean communityTextContains(CrossDocumentCommunity community, String normalizedTerm) {
        if (community == null || StrUtil.isBlank(normalizedTerm)) {
            return false;
        }
        if (normalizedContains(community.title(), normalizedTerm)
            || normalizedContains(community.summary(), normalizedTerm)) {
            return true;
        }
        for (String coreEntityName : community.reportProfile().coreEntityNames()) {
            if (normalizedContains(coreEntityName, normalizedTerm)) {
                return true;
            }
        }
        for (String canonicalKey : community.canonicalGroupKeys()) {
            if (normalizedContains(canonicalKey, normalizedTerm)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCommunityFocusTerm(String normalizedTerm) {
        if (StrUtil.isBlank(normalizedTerm)) {
            return false;
        }
        if (normalizedTerm.length() >= 4 || containsLatinOrDigit(normalizedTerm)) {
            return true;
        }
        return false;
    }

    private CommunityTopicGrounding assessCommunityTopicGrounding(CrossDocumentCommunity community,
                                                                  Map<String, RelationGroup> relationGroupByKey,
                                                                  Map<Long, SuperAgentKgEvidence> evidenceMap,
                                                                  CommunityTopicContext topicContext,
                                                                  QueryProfile queryProfile) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        double score = 0D;
        if (queryProfile != null && queryProfile.communityIds().contains(community.id())) {
            score += 0.82D;
            reasons.add("queryCommunityId");
        }
        if (queryProfile != null && CollUtil.isNotEmpty(queryProfile.communityIds()) && reasons.contains("queryCommunityId")) {
            return new CommunityTopicGrounding(true, score, List.copyOf(reasons));
        }

        boolean focusMatched = false;
        if (topicContext != null && CollUtil.isNotEmpty(topicContext.focusTerms())) {
            for (String focusTerm : topicContext.focusTerms()) {
                CommunityFocusMatch match = matchCommunityFocusTerm(community, evidenceMap, focusTerm);
                if (match.matched()) {
                    score += match.score();
                    reasons.addAll(match.reasons());
                    focusMatched = true;
                }
            }
        }

        double relationGroundingScore = communityRelationProfileScore(community, relationGroupByKey, queryProfile, reasons);
        if (relationGroundingScore > 0D) {
            score += relationGroundingScore;
        }
        boolean relationGrounded = reasons.contains("queryRelationType")
            || reasons.contains("queryRelationId")
            || reasons.contains("answerTypeEndpoint");

        if (focusMatched) {
            return new CommunityTopicGrounding(true, Math.min(score, 1.25D), List.copyOf(reasons));
        }
        if (relationGrounded && queryProfile != null && !queryProfile.communityQuestion()) {
            return new CommunityTopicGrounding(true, Math.min(score, 1.0D), List.copyOf(reasons));
        }
        if (queryProfile != null
            && queryProfile.communityQuestion()
            && CollUtil.isEmpty(topicContext == null ? null : topicContext.focusTerms())
            && relationGrounded) {
            return new CommunityTopicGrounding(true, Math.min(score, 0.86D), List.copyOf(reasons));
        }
        return CommunityTopicGrounding.empty();
    }

    private CommunityFocusMatch matchCommunityFocusTerm(CrossDocumentCommunity community,
                                                        Map<Long, SuperAgentKgEvidence> evidenceMap,
                                                        String focusTerm) {
        if (community == null || StrUtil.isBlank(focusTerm)) {
            return CommunityFocusMatch.empty();
        }
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        double score = 0D;
        if (normalizedContains(community.title(), focusTerm) || normalizedContains(community.summary(), focusTerm)) {
            score += 0.24D;
            reasons.add("focusTextMatch");
        }
        for (String coreEntityName : community.reportProfile().coreEntityNames()) {
            if (normalizedContains(coreEntityName, focusTerm)) {
                score += 0.30D;
                reasons.add("focusCoreEntityMatch");
                break;
            }
        }
        for (String canonicalKey : community.canonicalGroupKeys()) {
            if (normalizedContains(canonicalKey, focusTerm)) {
                score += 0.24D;
                reasons.add("focusCanonicalMatch");
                break;
            }
        }
        for (String relationGroupKey : community.relationGroupKeys()) {
            if (normalizedContains(relationGroupKey, focusTerm)) {
                score += 0.20D;
                reasons.add("focusRelationGroupMatch");
                break;
            }
        }
        double evidenceScore = communityEvidenceTermScore(community, evidenceMap, focusTerm);
        if (evidenceScore > 0D) {
            score += Math.max(0.28D, evidenceScore);
            reasons.add("focusEvidenceMatch");
        }
        if (score <= 0D) {
            return CommunityFocusMatch.empty();
        }
        return new CommunityFocusMatch(true, Math.min(score, 0.95D), List.copyOf(reasons));
    }

    private boolean normalizedContains(String text, String normalizedTerm) {
        String normalizedText = normalize(text);
        return StrUtil.isNotBlank(normalizedText)
            && StrUtil.isNotBlank(normalizedTerm)
            && (normalizedText.contains(normalizedTerm) || normalizedTerm.contains(normalizedText));
    }

    private boolean containsLatinOrDigit(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                return true;
            }
        }
        return false;
    }

    private double communityEvidenceTermScore(CrossDocumentCommunity community,
                                              Map<Long, SuperAgentKgEvidence> evidenceMap,
                                              String normalizedTerm) {
        if (community == null || evidenceMap == null || evidenceMap.isEmpty() || StrUtil.isBlank(normalizedTerm)) {
            return 0D;
        }
        double score = 0D;
        for (Long evidenceId : community.evidenceIds()) {
            SuperAgentKgEvidence evidence = evidenceMap.get(evidenceId);
            if (evidence == null) {
                continue;
            }
            String evidenceText = normalize(StrUtil.blankToDefault(evidence.getQuoteText(), "")
                + " " + StrUtil.blankToDefault(evidence.getSectionPath(), ""));
            if (evidenceText.contains(normalizedTerm)) {
                score += 0.14D;
            }
        }
        return Math.min(0.34D, score);
    }

    private double communityRelationProfileScore(CrossDocumentCommunity community,
                                                 Map<String, RelationGroup> relationGroupByKey,
                                                 QueryProfile queryProfile,
                                                 Set<String> reasons) {
        if (community == null || CollUtil.isEmpty(community.relationGroupKeys()) || queryProfile == null) {
            return 0D;
        }
        double score = 0D;
        for (String relationGroupKey : community.relationGroupKeys()) {
            RelationGroup group = relationGroupByKey == null ? null : relationGroupByKey.get(relationGroupKey);
            if (group == null) {
                continue;
            }
            String relationType = StrUtil.blankToDefault(group.relationType(), "ASSOCIATED_WITH")
                .trim()
                .toUpperCase(Locale.ROOT);
            if (queryProfile.relationTypes().contains(relationType)) {
                score += 0.24D;
                reasons.add("queryRelationType");
            }
            if (CollUtil.isNotEmpty(group.relationIds())
                && group.relationIds().stream().anyMatch(queryProfile.relationIds()::contains)) {
                score += 0.30D;
                reasons.add("queryRelationId");
            }
            if (relationGroupEndpointMatchesAnswerType(group, queryProfile)) {
                score += 0.10D;
                reasons.add("answerTypeEndpoint");
            }
            double groupQueryBoost = relationGroupQueryBoost(group, queryProfile);
            if (groupQueryBoost > 0D) {
                score += Math.min(0.18D, groupQueryBoost * 0.65D);
                reasons.add("relationGroupQuery");
            }
        }
        return Math.min(0.48D, score);
    }

    private double communityNoisePenalty(CrossDocumentCommunity community) {
        if (community == null) {
            return 0D;
        }
        double penalty = 0D;
        if (community.qualityProfile() != null && CollUtil.isNotEmpty(community.qualityProfile().noiseReasons())) {
            penalty += Math.min(0.14D, community.qualityProfile().noiseReasons().size() * 0.035D);
        }
        return Math.min(0.22D, penalty);
    }

    private QueryProfile analyzeQuery(String question, List<String> terms, int requestedMaxHops) {
        return new QueryProfile(
            "",
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            false,
            false,
            normalizeMaxHops(requestedMaxHops),
            JAVA_QUERY_PROFILE_SOURCE
        );
    }

    private boolean shouldAskAdvisor(String question,
                                     QueryProfile queryProfile,
                                     List<SuperAgentKgEntity> entities,
                                     List<SuperAgentKgCommunity> communities) {
        return queryPlanAdvisor != null
            && StrUtil.isNotBlank(question)
            && (CollUtil.isNotEmpty(entities) || CollUtil.isNotEmpty(communities));
    }

    private AdvisorProfileApplication applyAdvisorProfile(String question,
                                                          QueryProfile baseProfile,
                                                          List<SuperAgentKgEntity> entities,
                                                          List<SuperAgentKgRelation> relations,
                                                          List<SuperAgentKgCommunity> communities,
                                                          int topK,
                                                          int requestedMaxHops) {
        GraphRagQueryCatalog catalog = buildQueryCatalog(entities, relations, communities, topK);
        Optional<GraphRagQueryPlanAdvice> advice;
        try {
            advice = queryPlanAdvisor.advise(question, catalog);
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG 受控查询计划 advisor 失败，继续使用 Java 规则 profile: question='{}', message={}",
                question,
                exception.getMessage());
            return new AdvisorProfileApplication(baseProfile, true);
        }
        if (advice.isEmpty()) {
            return new AdvisorProfileApplication(baseProfile, false);
        }
        QueryProfile advisorProfile = validateAdvice(question, advice.get(), entities, relations, communities, requestedMaxHops);
        if (advisorProfile == null) {
            return new AdvisorProfileApplication(baseProfile, false);
        }
        return new AdvisorProfileApplication(mergeProfiles(baseProfile, advisorProfile), false);
    }

    private QueryProfile withJavaFocusEntities(QueryProfile baseProfile,
                                               List<SuperAgentKgEntity> entities,
                                               String normalizedQuestion) {
        if (baseProfile == null || StrUtil.isBlank(normalizedQuestion) || CollUtil.isEmpty(entities)) {
            return baseProfile;
        }
        LinkedHashSet<String> focusEntities = new LinkedHashSet<>(baseProfile.focusEntities());
        for (SuperAgentKgEntity entity : entities) {
            if (!isGraphSearchEntityUsable(entity) || !matchesEntityText(entity, normalizedQuestion)) {
                continue;
            }
            addNormalizedIfNotBlank(focusEntities, entity.getName());
            addNormalizedIfNotBlank(focusEntities, entity.getNormalizedName());
            for (String alias : entityAliases(entity)) {
                String normalizedAlias = normalize(alias);
                if (normalizedAlias.length() >= 2 && normalizedQuestion.contains(normalizedAlias)) {
                    focusEntities.add(normalizedAlias);
                }
            }
        }
        if (focusEntities.equals(baseProfile.focusEntities())) {
            return baseProfile;
        }
        return new QueryProfile(
            baseProfile.queryIntent(),
            baseProfile.relationTypes(),
            baseProfile.entityTypes(),
            baseProfile.entityIds(),
            baseProfile.relationIds(),
            baseProfile.communityIds(),
            baseProfile.entityNames(),
            baseProfile.answerTypeKeywords(),
            baseProfile.entitiesFromQuery(),
            focusEntities,
            baseProfile.genericIntentTerms(),
            baseProfile.relationQuestion(),
            baseProfile.communityQuestion(),
            baseProfile.maxHops(),
            baseProfile.sourceText()
        );
    }

    private EntityHintSeeds resolveEntityHintSeeds(List<String> hints, List<SuperAgentKgEntity> entities) {
        LinkedHashSet<String> receivedHints = new LinkedHashSet<>();
        if (hints != null) {
            hints.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .limit(8)
                .forEach(receivedHints::add);
        }
        LinkedHashSet<String> normalizedHints = receivedHints.stream()
            .map(this::normalize)
            .filter(value -> value.length() >= 2)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> usedSeeds = new LinkedHashSet<>();
        if (!normalizedHints.isEmpty() && CollUtil.isNotEmpty(entities)) {
            for (SuperAgentKgEntity entity : entities) {
                if (!isGraphSearchEntityUsable(entity)) {
                    continue;
                }
                for (String hint : normalizedHints) {
                    if (entityNameVariants(entity).contains(hint)) {
                        usedSeeds.add(hint);
                    }
                }
            }
        }
        return new EntityHintSeeds(
            hints != null && !hints.isEmpty(),
            List.copyOf(receivedHints),
            List.copyOf(normalizedHints),
            List.copyOf(usedSeeds)
        );
    }

    private QueryProfile withEntityHintSeeds(QueryProfile baseProfile, EntityHintSeeds entityHintSeeds) {
        if (baseProfile == null || entityHintSeeds == null || entityHintSeeds.usedSeeds().isEmpty()) {
            return baseProfile;
        }
        LinkedHashSet<String> entityNames = new LinkedHashSet<>(baseProfile.entityNames());
        entityNames.addAll(entityHintSeeds.usedSeeds());
        LinkedHashSet<String> entitiesFromQuery = new LinkedHashSet<>(baseProfile.entitiesFromQuery());
        entitiesFromQuery.addAll(entityHintSeeds.usedSeeds());
        LinkedHashSet<String> focusEntities = new LinkedHashSet<>(baseProfile.focusEntities());
        focusEntities.addAll(entityHintSeeds.usedSeeds());
        return new QueryProfile(
            baseProfile.queryIntent(),
            baseProfile.relationTypes(),
            baseProfile.entityTypes(),
            baseProfile.entityIds(),
            baseProfile.relationIds(),
            baseProfile.communityIds(),
            entityNames,
            baseProfile.answerTypeKeywords(),
            entitiesFromQuery,
            focusEntities,
            baseProfile.genericIntentTerms(),
            baseProfile.relationQuestion(),
            baseProfile.communityQuestion(),
            baseProfile.maxHops(),
            mergeSourceText(baseProfile.sourceText(), "query-understanding.entity-hints.v1")
        );
    }

    private GraphRagQueryCatalog buildQueryCatalog(List<SuperAgentKgEntity> entities,
                                                   List<SuperAgentKgRelation> relations,
                                                   List<SuperAgentKgCommunity> communities,
                                                   int topK) {
        Map<Long, SuperAgentKgEntity> entityMap = entities.stream()
            .collect(Collectors.toMap(SuperAgentKgEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        int entityLimit = Math.max(CATALOG_ENTITY_LIMIT, topK * 8);
        int relationLimit = Math.max(CATALOG_RELATION_LIMIT, topK * 12);
        int communityLimit = Math.max(CATALOG_COMMUNITY_LIMIT, topK * 4);
        return GraphRagQueryCatalog.builder()
            .entities(entities.stream()
                .filter(Objects::nonNull)
                .filter(this::isGraphSearchEntityUsable)
                .sorted(Comparator.comparingDouble(this::entityRankBoost).reversed())
                .limit(entityLimit)
                .map(entity -> GraphRagQueryCatalog.EntityItem.builder()
                    .entityId(entity.getId())
                    .name(entity.getName())
                    .normalizedName(entity.getNormalizedName())
                    .entityType(entity.getEntityType())
                    .aliases(entityAliases(entity))
                    .description(entity.getDescription())
                    .build())
                .toList())
            .relations(relations.stream()
                .filter(Objects::nonNull)
                .filter(relation -> {
                    SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
                    SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
                    return isGraphSearchEntityUsable(source) && isGraphSearchEntityUsable(target);
                })
                .sorted(Comparator.comparingDouble((SuperAgentKgRelation relation) -> relationRankBoost(
                    relation,
                    entityMap.get(relation.getSourceEntityId()),
                    entityMap.get(relation.getTargetEntityId())
                )).reversed())
                .limit(relationLimit)
                .map(relation -> {
                    SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
                    SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
                    return GraphRagQueryCatalog.RelationItem.builder()
                        .relationId(relation.getId())
                        .relationType(relation.getRelationType())
                        .sourceEntityId(relation.getSourceEntityId())
                        .sourceEntityName(source == null ? "" : source.getName())
                        .targetEntityId(relation.getTargetEntityId())
                        .targetEntityName(target == null ? "" : target.getName())
                        .description(relation.getDescription())
                        .build();
                })
                .toList())
            .communities(communities.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(this::communityRankBoost).reversed())
                .limit(communityLimit)
                .map(community -> GraphRagQueryCatalog.CommunityItem.builder()
                    .communityId(community.getId())
                    .title(community.getTitle())
                    .summary(community.getSummary())
                    .build())
                .toList())
            .build();
    }

    private QueryProfile validateAdvice(String question,
                                        GraphRagQueryPlanAdvice advice,
                                        List<SuperAgentKgEntity> entities,
                                        List<SuperAgentKgRelation> relations,
                                        List<SuperAgentKgCommunity> communities,
                                        int requestedMaxHops) {
        if (advice == null || !Boolean.TRUE.equals(advice.getGraphQuery())) {
            return null;
        }
        double confidence = numberValue(advice.getConfidence(), 0D);
        if (confidence < ADVISOR_CONFIDENCE_THRESHOLD) {
            return null;
        }
        Set<String> allowedEntityTypes = entities.stream()
            .map(SuperAgentKgEntity::getEntityType)
            .filter(StrUtil::isNotBlank)
            .map(item -> item.toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> allowedRelationTypes = relations.stream()
            .map(SuperAgentKgRelation::getRelationType)
            .filter(StrUtil::isNotBlank)
            .map(item -> item.toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> allowedEntityIds = entities.stream()
            .map(SuperAgentKgEntity::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> allowedRelationIds = relations.stream()
            .map(SuperAgentKgRelation::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> allowedCommunityIds = communities.stream()
            .map(SuperAgentKgCommunity::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        String queryIntent = normalizeQueryIntent(advice.getQueryIntent());
        LinkedHashSet<String> genericIntentTerms = normalizeEntitiesFromQuery(advice.getGenericIntentTerms(), question);
        LinkedHashSet<String> focusEntities = normalizeEntitiesFromQuery(advice.getFocusEntities(), question);
        LinkedHashSet<String> answerTypeKeywords = normalizeAllowedStrings(advice.getAnswerTypeKeywords(), allowedEntityTypes);
        LinkedHashSet<String> entityTypes = normalizeAllowedStrings(advice.getEntityTypes(), allowedEntityTypes);
        entityTypes.addAll(answerTypeKeywords);
        LinkedHashSet<String> relationTypes = normalizeAllowedStrings(advice.getRelationTypes(), allowedRelationTypes);
        LinkedHashSet<Long> entityIds = normalizeAllowedLongs(advice.getEntityIds(), allowedEntityIds);
        LinkedHashSet<Long> relationIds = normalizeAllowedLongs(advice.getRelationIds(), allowedRelationIds);
        LinkedHashSet<Long> communityIds = normalizeAllowedLongs(advice.getCommunityIds(), allowedCommunityIds);
        LinkedHashSet<String> entityNames = normalizeAllowedEntityNames(advice.getEntityNames(), entities);
        LinkedHashSet<String> entitiesFromQuery = normalizeEntitiesFromQuery(advice.getEntitiesFromQuery(), question);
        focusEntities.addAll(entitiesFromQuery);

        boolean relationQuestion = Boolean.TRUE.equals(advice.getRelationQuestion())
            || !relationTypes.isEmpty()
            || !relationIds.isEmpty();
        boolean communityQuestion = Boolean.TRUE.equals(advice.getCommunityQuestion()) || !communityIds.isEmpty();
        if (StrUtil.isBlank(queryIntent)
            && entityTypes.isEmpty()
            && relationTypes.isEmpty()
            && entityIds.isEmpty()
            && relationIds.isEmpty()
            && communityIds.isEmpty()
            && entityNames.isEmpty()
            && entitiesFromQuery.isEmpty()
            && focusEntities.isEmpty()) {
            return null;
        }
        return new QueryProfile(
            queryIntent,
            relationTypes,
            entityTypes,
            entityIds,
            relationIds,
            communityIds,
            entityNames,
            answerTypeKeywords,
            entitiesFromQuery,
            focusEntities,
            genericIntentTerms,
            relationQuestion,
            communityQuestion || "COMMUNITY_REPORT".equals(queryIntent),
            normalizeAdvisorMaxHops(advice.getMaxHops(), requestedMaxHops),
            ADVISOR_QUERY_PROFILE_SOURCE
        );
    }

    private QueryProfile mergeProfiles(QueryProfile baseProfile, QueryProfile advisorProfile) {
        String queryIntent = StrUtil.blankToDefault(advisorProfile.queryIntent(), baseProfile.queryIntent());
        LinkedHashSet<String> relationTypes = new LinkedHashSet<>(baseProfile.relationTypes());
        relationTypes.addAll(advisorProfile.relationTypes());
        LinkedHashSet<String> entityTypes = new LinkedHashSet<>(baseProfile.entityTypes());
        entityTypes.addAll(advisorProfile.entityTypes());
        LinkedHashSet<Long> entityIds = new LinkedHashSet<>(baseProfile.entityIds());
        entityIds.addAll(advisorProfile.entityIds());
        LinkedHashSet<Long> relationIds = new LinkedHashSet<>(baseProfile.relationIds());
        relationIds.addAll(advisorProfile.relationIds());
        LinkedHashSet<Long> communityIds = new LinkedHashSet<>(baseProfile.communityIds());
        communityIds.addAll(advisorProfile.communityIds());
        LinkedHashSet<String> entityNames = new LinkedHashSet<>(baseProfile.entityNames());
        entityNames.addAll(advisorProfile.entityNames());
        LinkedHashSet<String> answerTypeKeywords = new LinkedHashSet<>(baseProfile.answerTypeKeywords());
        answerTypeKeywords.addAll(advisorProfile.answerTypeKeywords());
        LinkedHashSet<String> entitiesFromQuery = new LinkedHashSet<>(baseProfile.entitiesFromQuery());
        entitiesFromQuery.addAll(advisorProfile.entitiesFromQuery());
        LinkedHashSet<String> focusEntities = new LinkedHashSet<>(baseProfile.focusEntities());
        focusEntities.addAll(advisorProfile.focusEntities());
        LinkedHashSet<String> genericIntentTerms = new LinkedHashSet<>(baseProfile.genericIntentTerms());
        genericIntentTerms.addAll(advisorProfile.genericIntentTerms());
        return new QueryProfile(
            queryIntent,
            relationTypes,
            entityTypes,
            entityIds,
            relationIds,
            communityIds,
            entityNames,
            answerTypeKeywords,
            entitiesFromQuery,
            focusEntities,
            genericIntentTerms,
            baseProfile.relationQuestion() || advisorProfile.relationQuestion(),
            baseProfile.communityQuestion() || advisorProfile.communityQuestion(),
            Math.max(baseProfile.maxHops(), advisorProfile.maxHops()),
            mergeSourceText(baseProfile.sourceText(), advisorProfile.sourceText())
        );
    }

    private LinkedHashSet<String> normalizeAllowedStrings(List<String> values, Set<String> allowedValues) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (CollUtil.isEmpty(values) || CollUtil.isEmpty(allowedValues)) {
            return result;
        }
        for (String value : values) {
            String normalized = StrUtil.blankToDefault(value, "").trim().toUpperCase(Locale.ROOT);
            if (allowedValues.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private LinkedHashSet<Long> normalizeAllowedLongs(List<Long> values, Set<Long> allowedValues) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (CollUtil.isEmpty(values) || CollUtil.isEmpty(allowedValues)) {
            return result;
        }
        for (Long value : values) {
            if (value != null && allowedValues.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private LinkedHashSet<String> normalizeAllowedEntityNames(List<String> entityNames,
                                                              List<SuperAgentKgEntity> entities) {
        LinkedHashSet<String> allowedNames = new LinkedHashSet<>();
        for (SuperAgentKgEntity entity : entities) {
            addNormalizedIfNotBlank(allowedNames, entity.getName());
            addNormalizedIfNotBlank(allowedNames, entity.getNormalizedName());
            for (String alias : entityAliases(entity)) {
                addNormalizedIfNotBlank(allowedNames, alias);
            }
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (CollUtil.isEmpty(entityNames)) {
            return result;
        }
        for (String entityName : entityNames) {
            String normalizedName = normalize(entityName);
            if (normalizedName.length() >= 2 && allowedNames.contains(normalizedName)) {
                result.add(normalizedName);
            }
        }
        return result;
    }

    private LinkedHashSet<String> normalizeEntitiesFromQuery(List<String> values, String question) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (CollUtil.isEmpty(values) || StrUtil.isBlank(question)) {
            return result;
        }
        String normalizedQuestion = normalize(question);
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized.length() >= 2 && normalizedQuestion.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private String normalizeQueryIntent(String queryIntent) {
        String normalized = StrUtil.blankToDefault(queryIntent, "").trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "RELATION_SEARCH", "COMMUNITY_REPORT", "NHOP_SEARCH", "ENTITY_LOOKUP", "GLOBAL_SUMMARY" -> normalized;
            default -> "";
        };
    }

    private boolean hasQuestionEntityAnchor(String normalizedQuestion, List<SuperAgentKgEntity> entities) {
        if (StrUtil.isBlank(normalizedQuestion) || CollUtil.isEmpty(entities)) {
            return false;
        }
        return entities.stream()
            .filter(Objects::nonNull)
            .filter(this::isGraphSearchEntityUsable)
            .anyMatch(entity -> matchesEntityText(entity, normalizedQuestion));
    }

    private void addNormalizedIfNotBlank(Set<String> target, String value) {
        String normalized = normalize(value);
        if (normalized.length() >= 2) {
            target.add(normalized);
        }
    }

    private int normalizeAdvisorMaxHops(Integer advisorMaxHops, int requestedMaxHops) {
        if (advisorMaxHops == null) {
            return normalizeMaxHops(requestedMaxHops);
        }
        return Math.min(normalizeMaxHops(requestedMaxHops), normalizeMaxHops(advisorMaxHops));
    }

    private int normalizeMaxHops(int maxHops) {
        return Math.max(1, Math.min(2, maxHops));
    }

    private String searchableEntityText(SuperAgentKgEntity entity) {
        StringBuilder builder = new StringBuilder();
        builder.append(StrUtil.blankToDefault(entity.getName(), "")).append(' ');
        builder.append(StrUtil.blankToDefault(entity.getNormalizedName(), "")).append(' ');
        builder.append(StrUtil.blankToDefault(entity.getDescription(), "")).append(' ');
        for (String alias : entityAliases(entity)) {
            builder.append(alias).append(' ');
        }
        return normalize(builder.toString());
    }

    private boolean matchesAdvisorEntityName(SuperAgentKgEntity entity, Set<String> entityNames) {
        if (CollUtil.isEmpty(entityNames) || entity == null) {
            return false;
        }
        if (entityNames.contains(normalize(entity.getName())) || entityNames.contains(normalize(entity.getNormalizedName()))) {
            return true;
        }
        for (String alias : entityAliases(entity)) {
            if (entityNames.contains(normalize(alias))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesQueryEntityPhrase(SuperAgentKgEntity entity, Set<String> queryEntityPhrases) {
        if (CollUtil.isEmpty(queryEntityPhrases) || entity == null) {
            return false;
        }
        String searchableText = searchableEntityText(entity);
        for (String phrase : queryEntityPhrases) {
            String normalizedPhrase = normalize(phrase);
            if (normalizedPhrase.length() < 2) {
                continue;
            }
            if (searchableText.contains(normalizedPhrase)) {
                return true;
            }
            for (String variant : entityNameVariants(entity)) {
                if (variant.contains(normalizedPhrase) || normalizedPhrase.contains(variant)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesEntityText(SuperAgentKgEntity entity, String normalizedText) {
        if (entity == null || StrUtil.isBlank(normalizedText)) {
            return false;
        }
        for (String variant : entityNameVariants(entity)) {
            if (variant.length() >= 2 && normalizedText.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    private List<String> entityNameVariants(SuperAgentKgEntity entity) {
        if (entity == null) {
            return List.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        addNormalizedIfNotBlank(variants, entity.getName());
        addNormalizedIfNotBlank(variants, entity.getNormalizedName());
        for (String alias : entityAliases(entity)) {
            addNormalizedIfNotBlank(variants, alias);
        }
        return variants.stream().filter(StrUtil::isNotBlank).toList();
    }

    private String mergeSourceText(String left, String right) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(left)) {
            values.addAll(List.of(left.split(",")));
        }
        if (StrUtil.isNotBlank(right)) {
            values.addAll(List.of(right.split(",")));
        }
        values.removeIf(StrUtil::isBlank);
        return String.join(",", values);
    }

    private List<String> entityAliases(SuperAgentKgEntity entity) {
        return crossDocumentIndexSupport.entityAliases(entity);
    }

    private double metadataConfidence(String metadataJson) {
        Map<String, Object> metadata = readMap(metadataJson);
        return numberValue(metadata.get("confidence"), 0D);
    }

    private double numberValue(Object value, double defaultValue) {
        return crossDocumentIndexSupport.numberValue(value, defaultValue);
    }

    private String stringValue(Object value) {
        return crossDocumentIndexSupport.stringValue(value);
    }

    private Map<String, Object> readMap(String json) {
        return crossDocumentIndexSupport.readMap(json);
    }

    private List<String> readStringList(Object value) {
        return crossDocumentIndexSupport.readStringList(value);
    }

    private List<String> extractTerms(String question) {
        if (StrUtil.isBlank(question)) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String trimmed = question.trim();
        if (trimmed.length() <= 40) {
            terms.add(trimmed);
        }
        for (String segment : trimmed.split("[\\s、，,；;：:（）()\\-的和及与或]+")) {
            String item = segment.trim();
            if (item.length() >= 2) {
                terms.add(item);
            }
        }
        Matcher matcher = ENGLISH_TERM_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            terms.add(matcher.group());
        }
        return terms.stream().limit(16).toList();
    }

    private String normalize(String text) {
        return crossDocumentIndexSupport.normalize(text);
    }

    private List<Long> readLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        }
        catch (Exception exception) {
            return List.of();
        }
    }

    private record ScoredEntity(SuperAgentKgEntity entity, double score) {
    }

    private record ScoredRelation(SuperAgentKgRelation relation, double score) {
    }

    private record ScoredCommunity(SuperAgentKgCommunity community, double score) {
    }

    private record ScoredCrossDocumentCommunity(CrossDocumentCommunity community, CommunityRankProfile rankProfile) {

        private double score() {
            return rankProfile == null ? 0D : rankProfile.score();
        }
    }

    private record CommunityRankProfile(double score, List<String> reasons) {

        private static CommunityRankProfile empty() {
            return new CommunityRankProfile(0D, List.of());
        }
    }

    private record CommunityRankContext(Map<String, RelationGroup> relationGroupByKey,
                                        Map<Long, SuperAgentKgEvidence> evidenceMap,
                                        CommunityTopicContext topicContext,
                                        QueryProfile queryProfile,
                                        Map<String, CommunityRankProfile> rankProfileByCommunityKey) {
    }

    private record RepresentativeCommunityEvidence(SuperAgentKgEvidence evidence,
                                                   RelationGroup relationGroup,
                                                   double score) {
    }

    private record EntityTrace(Long seedEntityId, String seedEntityName, String path) {
    }

    private record RelationTrace(Long seedEntityId, String seedEntityName, String path, String sourceText) {
    }

    private record EntityHintSeeds(boolean hintsArrived,
                                   List<String> receivedHints,
                                   List<String> normalizedHints,
                                   List<String> usedSeeds) {
    }

    private record AdvisorProfileApplication(QueryProfile profile, boolean failed) {
    }

    private record QueryProfile(String queryIntent,
                                Set<String> relationTypes,
                                Set<String> entityTypes,
                                Set<Long> entityIds,
                                Set<Long> relationIds,
                                Set<Long> communityIds,
                                Set<String> entityNames,
                                Set<String> answerTypeKeywords,
                                Set<String> entitiesFromQuery,
                                Set<String> focusEntities,
                                Set<String> genericIntentTerms,
                                boolean relationQuestion,
                                boolean communityQuestion,
                                int maxHops,
                                String sourceText) {
    }

    private record CommunityTopicContext(Set<String> focusTerms, Set<String> genericIntentTerms) {
    }

    private record CommunityTopicGrounding(boolean grounded, double score, List<String> reasons) {

        private static CommunityTopicGrounding empty() {
            return new CommunityTopicGrounding(false, 0D, List.of());
        }
    }

    private record CommunityFocusMatch(boolean matched, double score, List<String> reasons) {

        private static CommunityFocusMatch empty() {
            return new CommunityFocusMatch(false, 0D, List.of());
        }
    }
}
