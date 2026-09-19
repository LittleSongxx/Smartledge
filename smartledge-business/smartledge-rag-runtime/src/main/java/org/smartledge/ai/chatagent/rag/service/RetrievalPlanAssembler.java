package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.chatagent.rag.model.ConversationItemAnchor;
import org.smartledge.ai.chatagent.rag.model.ConversationStructureAnchor;
import org.smartledge.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.smartledge.ai.chatagent.rag.model.EvidenceAnchor;
import org.smartledge.ai.chatagent.rag.model.EvidenceApplicabilityPlan;
import org.smartledge.ai.chatagent.rag.model.GraphIntent;
import org.smartledge.ai.chatagent.rag.model.HistoryPlanningContext;
import org.smartledge.ai.chatagent.rag.model.QueryType;
import org.smartledge.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.smartledge.ai.rag.runtime.model.RagRuntimeOptions;
import org.smartledge.ai.chatagent.rag.model.RankFeatureBundle;
import org.smartledge.ai.chatagent.rag.model.RaptorIntent;
import org.smartledge.ai.chatagent.rag.model.RetrievalChannelPlan;
import org.smartledge.ai.chatagent.rag.model.RetrievalContextAnchor;
import org.smartledge.ai.chatagent.rag.model.RetrievalExecutionQuery;
import org.smartledge.ai.chatagent.rag.model.RetrievalIntent;
import org.smartledge.ai.chatagent.rag.model.RetrievalMetadataFilters;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.model.RetrievalQuestionPlan;
import org.smartledge.ai.chatagent.rag.model.RetrievalRouteCandidate;
import org.smartledge.ai.chatagent.rag.model.RetrievalRoutePlan;
import org.smartledge.ai.chatagent.rag.model.RouteScopeAuthorizationMode;
import org.smartledge.ai.chatagent.rag.model.StructureNavigationIntent;
import org.smartledge.ai.chatagent.rag.model.TableIntent;
import org.smartledge.ai.chatagent.rag.model.AnswerShapeRequirement;
import org.smartledge.ai.rag.runtime.model.route.DocumentRouteCandidate;
import org.smartledge.ai.rag.runtime.model.route.KnowledgeRouteDecision;
import org.smartledge.ai.rag.runtime.model.route.ScopeRouteCandidate;
import org.smartledge.ai.rag.runtime.model.route.TopicRouteCandidate;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.smartledge.enums.RetrievalChannelEnum;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Assembles and validates the complete retrieval contract at one application boundary. */
@Service
public class RetrievalPlanAssembler {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern DOCUMENT_TITLE_PATTERN = Pattern.compile("《([^》]{2,80})》");
    private static final Pattern DOCUMENT_FILE_PATTERN = Pattern.compile("([\\w\\u4e00-\\u9fff.-]{2,80}\\.(?:pdf|md|txt|docx|png))", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECIMAL_SECTION_PATTERN = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d+)+)(?!\\d)");
    private static final Pattern NAMED_SECTION_PATTERN = Pattern.compile("(第\\s*[一二三四五六七八九十百0-9]+\\s*[章节条部分])|(附录\\s*[A-Za-z一二三四五六七八九十0-9]+)");
    private static final double STRUCTURE_FILTER_CONFIDENCE_THRESHOLD = 0.65D;
    private static final double EVIDENCE_APPLICABILITY_CONFIDENCE_THRESHOLD = 0.72D;

    public RetrievalPlan assemble(AssemblyInput input) {
        if (input == null) {
            throw new IllegalArgumentException("RetrievalPlan assembly input is required");
        }
        RagRuntimeOptions runtime = input.getRuntimeOptions() == null
            ? RagRuntimeOptions.from(null)
            : input.getRuntimeOptions();
        RetrievalQuestionPlan questionPlan = buildQuestionPlan(input);
        QueryUnderstandingResult understanding = input.getQueryUnderstanding();
        RagRuntimeOptions.HybridOptions hybrid = runtime.getHybrid() == null
            ? RagRuntimeOptions.HybridOptions.from(null)
            : runtime.getHybrid();
        TableIntent tableIntent = buildTableIntent(understanding);
        GraphIntent graphIntent = buildGraphIntent(understanding, runtime);
        RaptorIntent raptorIntent = buildRaptorIntent(understanding, runtime);

        List<RetrievalChannelPlan> channels = List.of(
            channel(RetrievalChannelEnum.VECTOR, true, runtime.getVectorTopK(), runtime.getChannelTimeoutMs(), hybrid.getVectorWeight(), runtime.getMinVectorSimilarity(), 0D),
            channel(RetrievalChannelEnum.KEYWORD, runtime.isKeywordChannelEnabled(), runtime.getKeywordTopK(), runtime.getChannelTimeoutMs(), hybrid.getKeywordWeight(), 0D, runtime.getKeywordRelativeScoreFloor()),
            channel(RetrievalChannelEnum.TABLE, expensiveChannelEnabled(runtime.isTableChannelEnabled(), tableIntent.isRequested(), runtime.isForceExpensiveChannels()), runtime.getCandidateTopK(), runtime.getChannelTimeoutMs(), hybrid.getTableWeight(), 0D, 0D),
            channel(RetrievalChannelEnum.GRAPH_RAG, expensiveChannelEnabled(runtime.isGraphRagChannelEnabled(), graphIntent.isRequested(), runtime.isForceExpensiveChannels()), runtime.getGraphRagTopK(), runtime.getChannelTimeoutMs(), hybrid.getGraphRagWeight(), 0D, 0D),
            channel(RetrievalChannelEnum.RAPTOR, expensiveChannelEnabled(runtime.isRaptorChannelEnabled(), raptorIntent.isRequested(), runtime.isForceExpensiveChannels()), runtime.getRaptorTopK(), runtime.getChannelTimeoutMs(), hybrid.getRaptorWeight(), 0D, 0D)
        );

        RetrievalPlan plan = RetrievalPlan.builder()
            .questionPlan(questionPlan)
            .chatMode(input.getChatMode())
            .primaryIntent(primaryIntent(input.getNavigationDecision()))
            .suggestedIntents(copySuggestedIntents(understanding))
            .scopeMode(input.getKnowledgeBaseSelectionMode())
            .knowledgeBaseIds(copyIds(input.getKnowledgeBaseIds()))
            .allowedDocumentScope(copyIds(input.getAllowedDocumentIds()))
            .documentScope(copyIds(input.getDocumentScope()))
            .taskScope(copyIds(input.getTaskScope()))
            .metadataFilters(buildMetadataFilters(input, questionPlan))
            .evidenceApplicabilityPlan(buildEvidenceApplicabilityPlan(questionPlan.getCurrentQuestion(), understanding))
            .channels(channels)
            .structureNavigation(copyStructureNavigation(understanding == null ? null : understanding.getStructureNavigationIntent()))
            .navigationAction(input.getNavigationDecision() == null ? null : input.getNavigationDecision().getNavigationAction())
            .structureNavigationResult(copyStructureNavigationResult(input.getNavigationDecision()))
            .structureAnchor(copyStructureAnchor(input.getNavigationDecision()))
            .itemAnchor(copyItemAnchor(input.getNavigationDecision()))
            .tableIntent(tableIntent)
            .graphIntent(graphIntent)
            .raptorIntent(raptorIntent)
            .routePlan(buildRoutePlan(input))
            .rankFeatures(buildRankFeatures(hybrid))
            .candidateWindow(runtime.getCandidateTopK())
            .rerankWindow(runtime.getRerankCandidateTopK())
            .rerankRequested(runtime.isRerankEnabled())
            .finalEvidenceBudget(runtime.getFinalTopK())
            .minEvidenceConfidence(runtime.getMinEvidenceConfidence())
            .subQuestionTimeoutMs(runtime.getSubQuestionTimeoutMs())
            .source("retrieval-plan-assembler")
            .reasons(List.of(
                "Scope, query boundaries, filters, route candidates, channel parameters and ranking windows were assembled once.",
                "Every execution query is compiled once into the only provider-facing RetrievalExecutionRequest."
            ))
            .build();
        plan.validateForExecution();
        return plan;
    }

    private RetrievalQuestionPlan buildQuestionPlan(AssemblyInput input) {
        String currentQuestion = normalizeText(input.getOriginalQuestion());
        String rewrittenQuestion = normalizeText(input.getRewrittenQuestion());
        String normalizedQuery = firstNonBlank(rewrittenQuestion, currentQuestion);
        boolean followUp = input.getQueryUnderstanding() != null
            && input.getQueryUnderstanding().getQueryType() == QueryType.FOLLOW_UP;
        List<RetrievalContextAnchor> inheritedAnchors = followUp
            ? buildInheritedAnchors(input.getScopedEvidenceAnchors())
            : List.of();
        List<String> contextHints = inheritedAnchors.stream().map(this::formatContextAnchor).toList();
        LinkedHashSet<String> subQuestions = new LinkedHashSet<>();
        if (input.getRewriteSubQuestions() != null) {
            input.getRewriteSubQuestions().stream()
                .map(this::normalizeText)
                .filter(StrUtil::isNotBlank)
                .forEach(subQuestions::add);
        }
        if (subQuestions.isEmpty() && StrUtil.isNotBlank(normalizedQuery)) {
            subQuestions.add(normalizedQuery);
        }
        List<RetrievalExecutionQuery> executionQueries = new ArrayList<>();
        int index = 1;
        for (String subQuestion : subQuestions) {
            executionQueries.add(RetrievalExecutionQuery.builder()
                .index(index++)
                .sourceQuestion(subQuestion)
                .normalizedQuery(subQuestion)
                .executionQuery(subQuestion)
                .contextHints(new ArrayList<>(contextHints))
                .build());
        }
        return RetrievalQuestionPlan.builder()
            .currentQuestion(currentQuestion)
            .rewrittenQuestion(rewrittenQuestion)
            .normalizedQuery(normalizedQuery)
            .executionQueries(executionQueries)
            .followUp(followUp)
            .historyInherited(!inheritedAnchors.isEmpty())
            .historyInheritanceSource(inheritedAnchors.isEmpty() ? "NONE" : "FINAL_EVIDENCE_ANCHOR")
            .inheritedContextAnchors(inheritedAnchors)
            .build();
    }

    private List<RetrievalContextAnchor> buildInheritedAnchors(List<EvidenceAnchor> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, RetrievalContextAnchor> unique = new LinkedHashMap<>();
        for (EvidenceAnchor anchor : anchors) {
            if (anchor == null || anchor.getDocumentId() == null) {
                continue;
            }
            RetrievalContextAnchor inherited = RetrievalContextAnchor.builder()
                .documentId(anchor.getDocumentId())
                .sectionPath(normalizeText(anchor.getSectionPath()))
                .structureNodeId(anchor.getStructureNodeId())
                .parentBlockId(anchor.getParentBlockId())
                .chunkId(anchor.getChunkId())
                .source("FINAL_EVIDENCE_ANCHOR")
                .build();
            String identity = inherited.getDocumentId() + ":" + inherited.getStructureNodeId() + ":"
                + inherited.getParentBlockId() + ":" + inherited.getChunkId();
            unique.putIfAbsent(identity, inherited);
        }
        return unique.values().stream().limit(5).toList();
    }

    private String formatContextAnchor(RetrievalContextAnchor anchor) {
        List<String> parts = new ArrayList<>();
        addContextPart(parts, "documentId", anchor.getDocumentId());
        addContextPart(parts, "sectionPath", anchor.getSectionPath());
        addContextPart(parts, "structureNodeId", anchor.getStructureNodeId());
        addContextPart(parts, "parentBlockId", anchor.getParentBlockId());
        addContextPart(parts, "chunkId", anchor.getChunkId());
        return String.join("; ", parts);
    }

    private void addContextPart(List<String> parts, String name, Object value) {
        if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
            parts.add(name + "=" + value);
        }
    }

    private RetrievalMetadataFilters buildMetadataFilters(AssemblyInput input, RetrievalQuestionPlan questionPlan) {
        String normalizedQuery = questionPlan.getNormalizedQuery();
        QueryUnderstandingResult understanding = input.getQueryUnderstanding();
        LinkedHashSet<String> sections = new LinkedHashSet<>();
        LinkedHashSet<String> years = new LinkedHashSet<>();
        if (hasAuthorizedStructureFilter(understanding)) {
            collectMatches(DECIMAL_SECTION_PATTERN, normalizedQuery, sections);
            collectMatches(NAMED_SECTION_PATTERN, normalizedQuery, sections);
        }
        collectAuthorizedStructureSections(understanding, sections);
        collectMatches(YEAR_PATTERN, normalizedQuery, years);
        LinkedHashSet<String> documentNames = new LinkedHashSet<>();
        collectMatches(DOCUMENT_TITLE_PATTERN, normalizedQuery, documentNames);
        collectMatches(DOCUMENT_FILE_PATTERN, normalizedQuery, documentNames);
        DocumentFocus documentFocus = buildDocumentFocus(input, questionPlan);
        // grounded 的产品名建议同时进入 documentNameHints：即使未授权收窄，
        // ES 的 documentName 软加权（BM25 软加权是 AGENTS.md 明确允许的用法）也能压制同族噪声。
        documentNames.addAll(documentFocus.groundedSuggestions());
        return RetrievalMetadataFilters.builder()
            .documentNameHints(documentNames.stream().limit(8).toList())
            .sectionPathHints(sections.stream().limit(8).toList())
            .yearHints(years.stream().limit(8).toList())
            .entityHints(normalizeStrings(understanding == null ? null : understanding.getEntities(), 8))
            .documentIdHints(documentFocus.authorizedDocumentIds())
            .documentFocusReason(documentFocus.reason())
            .build();
    }

    /**
     * 产品级消歧授权：查询理解点名的产品/文档名建议，经四道独立授权后才允许收窄：
     * AUTO 模式、置信度阈值、原问题 grounding（建议必须是原问题字面子串）、
     * 与 allowed scope 文档名的归一化唯一匹配（每个建议恰好命中一份文档）。
     * 任何一条不满足都降级为 advisory（只进软加权，不收窄）。
     * 与 buildEvidenceApplicabilityPlan 同构：LLM 只建议，Java 独立授权。
     */
    private DocumentFocus buildDocumentFocus(AssemblyInput input, RetrievalQuestionPlan questionPlan) {
        QueryUnderstandingResult understanding = input.getQueryUnderstanding();
        List<String> suggestions = normalizeStrings(
            understanding == null ? null : understanding.getDocumentScopeSuggestions(), 3);
        Map<Long, String> allowedNames = input.getAllowedDocumentNames();
        if (suggestions.isEmpty()) {
            return DocumentFocus.advisory(List.of(), "No document scope suggestion from query understanding");
        }
        if (input.getChatMode() != ChatQueryMode.AUTO_DOCUMENT) {
            return DocumentFocus.advisory(suggestions, "Document focus only applies to AUTO_DOCUMENT mode");
        }
        if (normalizeConfidence(understanding.getConfidence()) < EVIDENCE_APPLICABILITY_CONFIDENCE_THRESHOLD) {
            return DocumentFocus.advisory(suggestions, "Query understanding confidence is below the document focus authorization threshold");
        }
        if (allowedNames == null || allowedNames.isEmpty()) {
            return DocumentFocus.advisory(suggestions, "Allowed scope document names are unavailable for matching");
        }
        String normalizedQuestion = normalizeEntityGroundingText(questionPlan.getCurrentQuestion());
        List<String> grounded = suggestions.stream()
            .filter(suggestion -> normalizedQuestion.contains(normalizeEntityGroundingText(suggestion)))
            .toList();
        if (grounded.isEmpty()) {
            return DocumentFocus.advisory(List.of(), "Document scope suggestions are not grounded in the current original question");
        }
        LinkedHashSet<Long> focused = new LinkedHashSet<>();
        for (String suggestion : grounded) {
            String token = normalizeDocumentToken(suggestion);
            if (token.isEmpty()) {
                return DocumentFocus.advisory(grounded, "Document scope suggestion normalizes to an empty token");
            }
            List<Long> matched = allowedNames.entrySet().stream()
                .filter(entry -> normalizeDocumentToken(entry.getValue()).contains(token))
                .map(Map.Entry::getKey)
                .toList();
            if (matched.size() != 1) {
                return DocumentFocus.advisory(grounded,
                    matched.isEmpty()
                        ? "Document scope suggestion matches no document in the allowed scope"
                        : "Document scope suggestion is ambiguous across documents in the allowed scope");
            }
            focused.add(matched.get(0));
        }
        return DocumentFocus.authorized(focused.stream().toList(), grounded,
            "Current-question grounded product reference uniquely matched allowed-scope documents");
    }

    /** 文档名/建议的归一化匹配键：小写、去扩展名、去分隔符，仅保留字母数字与 CJK。 */
    static String normalizeDocumentToken(String value) {
        if (value == null) {
            return "";
        }
        String lowered = value.toLowerCase().replaceAll("\\.(pdf|md|txt|docx|png|jpg|jpeg|xlsx)$", "");
        return lowered.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fff]", "");
    }

    /** 产品级消歧的授权结论（authorized ids + grounded 建议 + 原因），随计划可观测。 */
    record DocumentFocus(List<Long> authorizedDocumentIds, List<String> groundedSuggestions, String reason) {

        static DocumentFocus advisory(List<String> groundedSuggestions, String reason) {
            return new DocumentFocus(List.of(), groundedSuggestions == null ? List.of() : groundedSuggestions, reason);
        }

        static DocumentFocus authorized(List<Long> ids, List<String> groundedSuggestions, String reason) {
            return new DocumentFocus(ids == null ? List.of() : ids, groundedSuggestions, reason);
        }
    }

    private EvidenceApplicabilityPlan buildEvidenceApplicabilityPlan(String currentQuestion,
                                                                     QueryUnderstandingResult understanding) {
        List<String> targets = normalizeStrings(understanding == null ? null : understanding.getTargetEntities(), 8);
        List<String> excluded = normalizeStrings(understanding == null ? null : understanding.getExcludedEntities(), 8);
        String source = understanding == null ? "NONE" : StrUtil.blankToDefault(understanding.getSource(), "query-understanding");
        if (targets.isEmpty() || excluded.isEmpty()) {
            return EvidenceApplicabilityPlan.advisory(targets, excluded, source,
                "Target and excluded entity suggestions are both required for exclusion authorization");
        }
        if (normalizeConfidence(understanding.getConfidence()) < EVIDENCE_APPLICABILITY_CONFIDENCE_THRESHOLD) {
            return EvidenceApplicabilityPlan.advisory(targets, excluded, source,
                "Query understanding confidence is below the applicability authorization threshold");
        }
        if (understanding.getAnswerShapePlan() == null
            || !understanding.getAnswerShapePlan().requires(AnswerShapeRequirement.NEGATIVE_BOUNDARY)) {
            return EvidenceApplicabilityPlan.advisory(targets, excluded, source,
                "Negative-boundary intent is not authorized");
        }
        Set<String> normalizedTargets = normalizedEntitySet(targets);
        Set<String> normalizedExcluded = normalizedEntitySet(excluded);
        if (normalizedTargets.size() != targets.size() || normalizedExcluded.size() != excluded.size()
            || normalizedTargets.stream().anyMatch(normalizedExcluded::contains)) {
            return EvidenceApplicabilityPlan.advisory(targets, excluded, source,
                "Target and excluded entities must be stable and disjoint");
        }
        String normalizedQuestion = normalizeEntityGroundingText(currentQuestion);
        boolean grounded = normalizedTargets.stream().allMatch(entity -> normalizedQuestion.indexOf(entity) >= 0)
            && normalizedExcluded.stream().allMatch(entity -> normalizedQuestion.indexOf(entity) >= 0);
        if (!grounded) {
            return EvidenceApplicabilityPlan.advisory(targets, excluded, source,
                "Every target and excluded entity must be grounded in the current original question");
        }
        return EvidenceApplicabilityPlan.authorizedExclusion(targets, excluded, source,
            "Current-question negative boundary authorized entity applicability exclusion");
    }

    private Set<String> normalizedEntitySet(List<String> entities) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String entity : entities) {
            String value = normalizeEntityGroundingText(entity);
            if (value.length() >= 2) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private String normalizeEntityGroundingText(String value) {
        return Normalizer.normalize(StrUtil.blankToDefault(value, ""), Normalizer.Form.NFKC)
            .replaceAll("\\s+", "")
            .toLowerCase(Locale.ROOT)
            .trim();
    }

    private void collectAuthorizedStructureSections(QueryUnderstandingResult understanding,
                                                     LinkedHashSet<String> sections) {
        if (!hasAuthorizedStructureFilter(understanding)) {
            return;
        }
        StructureNavigationIntent intent = understanding.getStructureNavigationIntent();
        String anchorSectionPath = normalizeText(intent.getAnchorSectionPath());
        if (StrUtil.isNotBlank(anchorSectionPath)) {
            sections.add(anchorSectionPath);
        }
        normalizeStrings(intent.getSectionAnchors(), 8).forEach(sections::add);
    }

    private boolean hasAuthorizedStructureFilter(QueryUnderstandingResult understanding) {
        if (understanding == null
            || understanding.getQueryType() != QueryType.STRUCTURE_NAVIGATION
            || normalizeConfidence(understanding.getConfidence()) < STRUCTURE_FILTER_CONFIDENCE_THRESHOLD) {
            return false;
        }
        StructureNavigationIntent intent = understanding.getStructureNavigationIntent();
        return intent != null
            && normalizeConfidence(intent.getConfidence()) >= STRUCTURE_FILTER_CONFIDENCE_THRESHOLD
            && intent.getOperations() != null
            && !intent.getOperations().isEmpty()
            && intent.getOperations().stream().allMatch(Objects::nonNull);
    }

    private void collectMatches(Pattern pattern, String text, LinkedHashSet<String> target) {
        Matcher matcher = pattern.matcher(StrUtil.blankToDefault(text, ""));
        while (matcher.find()) {
            String value = matcher.groupCount() > 0 && matcher.group(1) != null ? matcher.group(1) : matcher.group();
            if (StrUtil.isNotBlank(value)) {
                target.add(value.replaceAll("\\s+", ""));
            }
        }
    }

    private RetrievalChannelPlan channel(RetrievalChannelEnum channel,
                                         boolean enabled,
                                         int topK,
                                         long timeoutMs,
                                         double weight,
                                         double minimumScore,
                                         double relativeScoreFloor) {
        return RetrievalChannelPlan.builder()
            .channelName(channel.getName())
            .enabled(enabled)
            .topK(topK)
            .timeoutMs(timeoutMs)
            .budget(topK)
            .weight(weight)
            .minimumScore(minimumScore)
            .relativeScoreFloor(relativeScoreFloor)
            .build();
    }

    private RetrievalIntent primaryIntent(DocumentNavigationDecision decision) {
        return decision == null || decision.getRetrievalIntent() == null
            ? RetrievalIntent.GENERAL
            : decision.getRetrievalIntent();
    }

    private List<RetrievalIntent> copySuggestedIntents(QueryUnderstandingResult understanding) {
        return understanding == null || understanding.getChannels() == null
            ? List.of()
            : understanding.getChannels().stream().filter(Objects::nonNull).distinct().toList();
    }

    private TableIntent buildTableIntent(QueryUnderstandingResult understanding) {
        return TableIntent.builder()
            .requested(channelRequested(understanding, QueryType.TABLE_QUERY, RetrievalIntent.TABLE))
            .tableOps(normalizeStrings(understanding == null ? null : understanding.getTableOps(), 8))
            .source(understanding == null ? "NONE" : StrUtil.blankToDefault(understanding.getSource(), "query-understanding"))
            .build();
    }

    private GraphIntent buildGraphIntent(QueryUnderstandingResult understanding, RagRuntimeOptions runtime) {
        return GraphIntent.builder()
            .requested(channelRequested(understanding, QueryType.GRAPH_RELATION, RetrievalIntent.GRAPH_RAG))
            .entities(normalizeStrings(understanding == null ? null : understanding.getEntities(), 8))
            .targetEntities(normalizeStrings(understanding == null ? null : understanding.getTargetEntities(), 8))
            .maxHops(runtime.getGraphRagMaxHops())
            .source(understanding == null ? "NONE" : StrUtil.blankToDefault(understanding.getSource(), "query-understanding"))
            .build();
    }

    private RaptorIntent buildRaptorIntent(QueryUnderstandingResult understanding, RagRuntimeOptions runtime) {
        boolean requested = channelRequested(understanding, QueryType.GLOBAL_SUMMARY, RetrievalIntent.RAPTOR);
        return RaptorIntent.builder()
            .requested(requested)
            .summaryRequested(requested)
            .sourceChunkTopK(runtime.getRaptorSourceChunkTopK())
            .source(understanding == null ? "NONE" : StrUtil.blankToDefault(understanding.getSource(), "query-understanding"))
            .build();
    }

    private RankFeatureBundle buildRankFeatures(RagRuntimeOptions.HybridOptions hybrid) {
        return RankFeatureBundle.builder()
            .enabledFeatures(List.of("CHANNEL_RRF"))
            .rankWeight(hybrid.getRankWeight())
            .originalScoreWeight(hybrid.getOriginalScoreWeight())
            .metadataBoostWeight(hybrid.getMetadataBoostWeight())
            .maxMetadataBoost(hybrid.getMaxMetadataBoost())
            .source("PERSISTED_INDEX_METADATA")
            .build();
    }

    private RetrievalRoutePlan buildRoutePlan(AssemblyInput input) {
        KnowledgeRoutePlan route = input.getKnowledgeRoutePlan();
        RouteScopeAuthorizationMode authorizationMode = resolveRouteAuthorizationMode(input, route);
        List<Long> authorizedDocumentIds = copyIds(route.getAuthorizedDocumentIds());
        List<Long> authorizedTaskIds = copyIds(route.getAuthorizedTaskIds());
        String scopeAuthorizationReason = StrUtil.blankToDefault(route.getScopeAuthorizationReason(), "");
        KnowledgeRouteDecision decision = route.getRouteDecision();
        List<RetrievalRouteCandidate> candidates = new ArrayList<>();
        if (decision != null) {
            if (decision.getScopes() != null) {
                decision.getScopes().stream().filter(Objects::nonNull).map(this::mapScopeCandidate).forEach(candidates::add);
            }
            if (decision.getTopics() != null) {
                decision.getTopics().stream().filter(Objects::nonNull).map(this::mapTopicCandidate).forEach(candidates::add);
            }
            if (decision.getDocuments() != null) {
                decision.getDocuments().stream().filter(Objects::nonNull).map(this::mapDocumentCandidate).forEach(candidates::add);
            }
            return RetrievalRoutePlan.builder()
                .source(StrUtil.blankToDefault(decision.getSource(), "NONE"))
                .status(StrUtil.blankToDefault(decision.getRouteStatus(), "FAILED"))
                .confidence(decision.getConfidence() == null ? 0D : decision.getConfidence().doubleValue())
                .degraded(decision.isDegraded())
                .degradedReasons(normalizeStrings(decision.getDegradedReasons(), 8))
                .topDocumentHintId(route.getRecommendedDocumentId())
                .topTaskHintId(route.getRecommendedTaskId())
                .authorizationMode(authorizationMode)
                .scopeAuthorizationReason(scopeAuthorizationReason)
                .authorizedDocumentIds(authorizedDocumentIds)
                .authorizedTaskIds(authorizedTaskIds)
                .candidates(candidates)
                .build();
        }

        String source = input.getChatMode() == ChatQueryMode.DOCUMENT
            ? "EXPLICIT_DOCUMENT_SCOPE"
            : "ALLOWED_DOCUMENT_SCOPE";
        List<Long> documentIds = copyIds(input.getDocumentScope());
        List<Long> taskIds = copyIds(input.getTaskScope());
        for (int index = 0; index < Math.min(documentIds.size(), taskIds.size()); index++) {
            Long documentId = documentIds.get(index);
            String displayName = Objects.equals(route.getRecommendedDocumentId(), documentId)
                ? route.getRecommendedDocumentName()
                : "";
            candidates.add(RetrievalRouteCandidate.builder()
                .candidateType("DOCUMENT")
                .documentId(documentId)
                .taskId(taskIds.get(index))
                .displayName(StrUtil.blankToDefault(displayName, ""))
                .score(0D)
                .reason(source.equals("EXPLICIT_DOCUMENT_SCOPE") ? "Explicit document scope" : "Allowed scope expansion")
                .source(source)
                .features(new LinkedHashMap<>())
                .build());
        }
        boolean degraded = input.getChatMode() == ChatQueryMode.AUTO_DOCUMENT;
        return RetrievalRoutePlan.builder()
            .source(source)
            .status(degraded ? "LOW_CONFIDENCE" : "SUCCESS")
            .confidence(degraded ? 0D : 1D)
            .degraded(degraded)
            .degradedReasons(degraded ? List.of("No scored route candidate; expanded inside allowed document scope") : List.of())
            .topDocumentHintId(route.getRecommendedDocumentId())
            .topTaskHintId(route.getRecommendedTaskId())
            .authorizationMode(authorizationMode)
            .scopeAuthorizationReason(scopeAuthorizationReason)
            .authorizedDocumentIds(authorizedDocumentIds)
            .authorizedTaskIds(authorizedTaskIds)
            .candidates(candidates)
            .build();
    }

    private RouteScopeAuthorizationMode resolveRouteAuthorizationMode(AssemblyInput input,
                                                                      KnowledgeRoutePlan route) {
        if (route == null || route.getAuthorizationMode() == null) {
            throw new IllegalArgumentException("Knowledge route authorization mode is required");
        }
        if (route.isClarificationRequired()
            || route.getAuthorizationMode() == RouteScopeAuthorizationMode.CLARIFICATION_REQUIRED) {
            throw new IllegalArgumentException("Clarification-required route authorization is not executable");
        }
        RouteScopeAuthorizationMode expected = switch (input.getChatMode()) {
            case DOCUMENT -> RouteScopeAuthorizationMode.EXPLICIT_DOCUMENT;
            case AUTO_DOCUMENT -> RouteScopeAuthorizationMode.KNOWLEDGE_BASE_ALLOWED_SCOPE;
            default -> throw new IllegalArgumentException("RetrievalPlan assembly requires DOCUMENT or AUTO_DOCUMENT mode");
        };
        if (route.getAuthorizationMode() != expected) {
            throw new IllegalArgumentException("Knowledge route authorization mode does not match chat mode");
        }
        return expected;
    }

    private RetrievalRouteCandidate mapScopeCandidate(ScopeRouteCandidate candidate) {
        return RetrievalRouteCandidate.builder()
            .candidateType("SCOPE")
            .candidateId(candidate.getScopeId())
            .scopeId(candidate.getScopeId())
            .displayName(candidate.getScopeName())
            .score(number(candidate.getScore()))
            .reason(candidate.getReason())
            .source(candidate.getSource())
            .features(features(candidate.getFeatures()))
            .build();
    }

    private RetrievalRouteCandidate mapTopicCandidate(TopicRouteCandidate candidate) {
        return RetrievalRouteCandidate.builder()
            .candidateType("TOPIC")
            .candidateId(candidate.getTopicId())
            .scopeId(candidate.getScopeId())
            .topicId(candidate.getTopicId())
            .displayName(candidate.getTopicName())
            .score(number(candidate.getScore()))
            .reason(candidate.getReason())
            .source(candidate.getSource())
            .features(features(candidate.getFeatures()))
            .build();
    }

    private RetrievalRouteCandidate mapDocumentCandidate(DocumentRouteCandidate candidate) {
        return RetrievalRouteCandidate.builder()
            .candidateType("DOCUMENT")
            .documentId(parseLong(candidate.getDocumentId()))
            .taskId(parseLong(candidate.getLastIndexTaskId()))
            .displayName(candidate.getDocumentName())
            .score(number(candidate.getScore()))
            .reason(candidate.getReason())
            .source(candidate.getSource())
            .features(features(candidate.getFeatures()))
            .build();
    }

    private StructureNavigationIntent copyStructureNavigation(StructureNavigationIntent intent) {
        if (intent == null) {
            return null;
        }
        return StructureNavigationIntent.builder()
            .operations(intent.getOperations() == null ? List.of() : new ArrayList<>(intent.getOperations()))
            .anchorStructureNodeId(intent.getAnchorStructureNodeId())
            .anchorSectionPath(intent.getAnchorSectionPath())
            .anchorCanonicalPath(intent.getAnchorCanonicalPath())
            .sectionAnchors(intent.getSectionAnchors() == null ? List.of() : new ArrayList<>(intent.getSectionAnchors()))
            .confidence(intent.getConfidence())
            .source(intent.getSource())
            .build();
    }

    private org.smartledge.ai.chatagent.rag.model.StructureNavigationResult copyStructureNavigationResult(DocumentNavigationDecision decision) {
        org.smartledge.ai.chatagent.rag.model.StructureNavigationResult result = decision == null
            ? null
            : decision.getStructureNavigationResult();
        if (result == null) {
            return null;
        }
        return org.smartledge.ai.chatagent.rag.model.StructureNavigationResult.builder()
            .documentId(result.getDocumentId())
            .parseTaskId(result.getParseTaskId())
            .anchorNodeId(result.getAnchorNodeId())
            .current(result.getCurrent())
            .parent(result.getParent())
            .previousSibling(result.getPreviousSibling())
            .nextSibling(result.getNextSibling())
            .directChildren(result.getDirectChildren() == null ? List.of() : new ArrayList<>(result.getDirectChildren()))
            .deterministic(result.isDeterministic())
            .missReason(result.getMissReason())
            .build();
    }

    private ConversationStructureAnchor copyStructureAnchor(DocumentNavigationDecision decision) {
        ConversationStructureAnchor anchor = decision == null ? null : decision.getStructureAnchor();
        if (anchor == null) {
            return null;
        }
        return ConversationStructureAnchor.builder()
            .rootSectionCode(anchor.getRootSectionCode())
            .rootSectionTitle(anchor.getRootSectionTitle())
            .targetSectionHint(anchor.getTargetSectionHint())
            .structureNodeId(anchor.getStructureNodeId())
            .canonicalPath(anchor.getCanonicalPath())
            .scopeMode(anchor.getScopeMode())
            .build();
    }

    private ConversationItemAnchor copyItemAnchor(DocumentNavigationDecision decision) {
        ConversationItemAnchor anchor = decision == null ? null : decision.getItemAnchor();
        if (anchor == null) {
            return null;
        }
        return ConversationItemAnchor.builder()
            .itemIndex(anchor.getItemIndex())
            .itemText(anchor.getItemText())
            .structureNodeId(anchor.getStructureNodeId())
            .canonicalPath(anchor.getCanonicalPath())
            .build();
    }

    private boolean expensiveChannelEnabled(boolean masterEnabled, boolean requested, boolean force) {
        return masterEnabled && (requested || force);
    }

    private boolean channelRequested(QueryUnderstandingResult understanding, QueryType queryType, RetrievalIntent intent) {
        if (understanding == null) {
            return false;
        }
        return understanding.getQueryType() == queryType
            || understanding.getChannels() != null && understanding.getChannels().contains(intent);
    }

    private List<String> normalizeStrings(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(this::normalizeText)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .limit(limit)
            .toList();
    }

    private Map<String, Double> features(Map<String, BigDecimal> values) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (StrUtil.isNotBlank(key) && value != null) {
                    result.put(key, value.doubleValue());
                }
            });
        }
        return result;
    }

    private double number(BigDecimal value) {
        return value == null ? 0D : value.doubleValue();
    }

    private double normalizeConfidence(double confidence) {
        if (confidence > 1D) {
            return confidence / 100D;
        }
        if (!Double.isFinite(confidence)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, confidence));
    }

    private Long parseLong(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Route candidate ID must be numeric", exception);
        }
    }

    private List<Long> copyIds(List<Long> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private String firstNonBlank(String primary, String fallback) {
        return StrUtil.isNotBlank(primary) ? primary : StrUtil.blankToDefault(fallback, "");
    }

    private String normalizeText(String value) {
        return StrUtil.blankToDefault(value, "").trim().replaceAll("\\s+", " ");
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssemblyInput {

        private ChatQueryMode chatMode;

        private String originalQuestion;

        private String rewrittenQuestion;

        @Builder.Default
        private List<String> rewriteSubQuestions = new ArrayList<>();

        private HistoryPlanningContext historyPlanningContext;

        private DocumentNavigationDecision navigationDecision;

        private QueryUnderstandingResult queryUnderstanding;

        private KnowledgeBaseSelectionMode knowledgeBaseSelectionMode;

        @Builder.Default
        private List<Long> knowledgeBaseIds = new ArrayList<>();

        @Builder.Default
        private List<Long> allowedDocumentIds = new ArrayList<>();

        @Builder.Default
        private List<Long> documentScope = new ArrayList<>();

        @Builder.Default
        private List<Long> taskScope = new ArrayList<>();

        /**
         * allowed scope 的文档名快照（id → 文档名），供产品级消歧的归一化唯一匹配。
         * 由编排层在解析 allowed scope 的同一处填充（同一授权来源，不新增授权）；
         * 缺省为空 map 时消歧自动降级 advisory，不影响既有行为。
         */
        @Builder.Default
        private Map<Long, String> allowedDocumentNames = new LinkedHashMap<>();

        private KnowledgeRoutePlan knowledgeRoutePlan;

        @Builder.Default
        private List<EvidenceAnchor> scopedEvidenceAnchors = new ArrayList<>();

        private RagRuntimeOptions runtimeOptions;
    }
}
