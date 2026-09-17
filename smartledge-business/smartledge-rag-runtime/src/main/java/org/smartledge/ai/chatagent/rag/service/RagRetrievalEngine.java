package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.model.ChannelExecutionView;
import org.smartledge.ai.chatagent.model.RetrievalResultView;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.EvidenceCandidatePools;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.EvidenceLineage;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.IdentityTransition;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.IdentityTransitionStage;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.IdentityTransitionType;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.RerankExecution;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.RerankExecutionReason;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.RerankExecutionStatus;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.RerankFailureStage;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.RerankRequestCandidate;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.RerankResultCandidate;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision.RerankStatus;
import org.smartledge.ai.chatagent.rag.model.ObservationPersistence;
import org.smartledge.ai.chatagent.rag.model.ObservationPersistence.ErrorType;
import org.smartledge.ai.chatagent.rag.model.RagRetrievalContext;
import org.smartledge.ai.chatagent.rag.model.RetrievalIntent;
import org.smartledge.ai.chatagent.rag.model.RetrievalExecutionRequest;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.model.SubQuestionChannelTrace;
import org.smartledge.ai.chatagent.rag.model.SubQuestionEvidence;
import org.smartledge.ai.chatagent.rag.retrieve.channel.RetrievalChannel;
import org.smartledge.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.smartledge.ai.chatagent.rag.support.EvidenceCandidateNormalizer;
import org.smartledge.ai.chatagent.rag.support.EvidenceCandidateIdentity;
import org.smartledge.ai.chatagent.rag.support.EvidenceIdentityResolver;
import org.smartledge.ai.chatagent.rag.support.IdentityTransitionRecorder;
import org.smartledge.ai.chatagent.rag.support.SearchReferenceMapper;
import org.smartledge.ai.chatagent.service.ConversationTraceRecorder;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.rag.runtime.port.DocumentEvidencePort;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.RetrievalChannelEnum;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @description: 服务层
 * @author: Song
 **/

@Slf4j
@Service
public class RagRetrievalEngine {

    private static final String FILTERED_BY_VECTOR_GATE = "FILTERED_BY_VECTOR_GATE";
    private static final String FILTERED_BY_KEYWORD_RELATIVE_SCORE = "FILTERED_BY_KEYWORD_RELATIVE_SCORE";
    private static final String FILTERED_BY_CHANNEL_GATE = "FILTERED_BY_CHANNEL_GATE";
    /**
     * 通过 channel gate 但未进入排序窗口时的原因。
     *
     * <p>gate 与最终证据策略之间只有排序窗口一个阶段，因此"拿不到最终策略原因"等价于
     * "被排序窗口按窗口上限截断"（或窗口构建失败）。这个原因本身是准确的，不是兜底占位。</p>
     */
    private static final String FILTERED_BY_SOURCE_RANKING_WINDOW = "FILTERED_BY_SOURCE_RANKING_WINDOW";
    private final List<RetrievalChannel> retrievalChannels;
    private final ChatRagProperties properties;
    private final RagRerankService ragRerankService;
    private final DocumentEvidencePort documentKnowledgeService;
    private final ExecutorService executorService;
    private final FinalEvidenceSelectionPolicy finalEvidenceSelectionPolicy;
    private final SourceRankingWindow sourceRankingWindow;
    private final RankFeatureService rankFeatureService;
    private final HybridFusionService hybridFusionService;
    private final ContextExpansionPlanner contextExpansionPlanner;

    public RagRetrievalEngine(List<RetrievalChannel> retrievalChannels,
                              ChatRagProperties properties,
                              RagRerankService ragRerankService,
                              DocumentEvidencePort documentKnowledgeService,
                              @Qualifier("chatRagExecutorService") ExecutorService executorService) {
        this.retrievalChannels = retrievalChannels;
        this.properties = properties;
        this.ragRerankService = ragRerankService;
        this.documentKnowledgeService = documentKnowledgeService;
        this.executorService = executorService;
        this.finalEvidenceSelectionPolicy = new FinalEvidenceSelectionPolicy(new EvidenceApplicabilityService());
        this.sourceRankingWindow = new SourceRankingWindow();
        this.rankFeatureService = new RankFeatureService();
        this.hybridFusionService = new HybridFusionService(rankFeatureService);
        this.contextExpansionPlanner = new ContextExpansionPlanner(documentKnowledgeService, properties);
    }

    public RagRetrievalContext retrieve(ConversationExecutionPlan plan, ConversationTraceRecorder traceRecorder) {
        if (plan == null) {
            throw new IllegalArgumentException("ConversationExecutionPlan is required for retrieval");
        }
        RetrievalPlan retrievalPlan = plan.getRetrievalPlan();
        if (retrievalPlan == null) {
            throw new IllegalArgumentException("RetrievalPlan is required for retrieval execution");
        }
        retrievalPlan.validateForExecution();
        String retrievalQuestion = retrievalPlan.getQuestionPlan().getNormalizedQuery();
        List<RetrievalExecutionRequest> executionRequests = RetrievalExecutionRequest.compile(retrievalPlan);
        validateChannelImplementations(executionRequests);

        // 本方法在调用方的租户作用域内执行（RagChatExecutor 已按本轮任务建立上下文）。
        // 扇出任务由线程池执行、并且可能被 CompletableFuture 的超时机制在别的时间点提交，
        // 因此显式捕获当前租户并随任务携带，而不是依赖提交线程的上下文。
        final Long tenantId = TenantContext.get();
        RagRetrievalContext context = new RagRetrievalContext();
        context.setRetrievalQuestion(retrievalQuestion);
        context.setUsedChannels(Collections.synchronizedList(new ArrayList<>()));
        context.setRetrievalNotes(Collections.synchronizedList(new ArrayList<>()));
        context.setExecutionRequests(executionRequests);
        context.setRequestReconciliations(executionRequests.stream()
            .map(request -> request.reconcile(retrievalPlan))
            .toList());

        List<CompletableFuture<SubQuestionEvidence>> futures = new ArrayList<>();
        for (RetrievalExecutionRequest request : executionRequests) {
            final int subQuestionIndex = request.subQuestionIndex();
            final String subQuestion = request.executionQuery();

            futures.add(retrieveSingleSubQuestionAsync(
                    request,
                    retrievalPlan,
                    context.getUsedChannels(),
                    context.getRetrievalNotes(),
                    traceRecorder,
                    tenantId
                )
                .orTimeout(resolveSubQuestionTimeoutMs(retrievalPlan), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {

                    Throwable rootCause = unwrapThrowable(throwable);
                    log.warn("子问题检索失败: subQuestionIndex={}, subQuestion='{}', exceptionType={}, message={}",
                        subQuestionIndex,
                        subQuestion,
                        rootCause == null ? "" : rootCause.getClass().getName(),
                        rootCause == null ? "" : rootCause.getMessage(),
                        throwable);
                    context.getRetrievalNotes().add("子问题" + subQuestionIndex + "检索失败或超时，已自动忽略。");
                    return new SubQuestionEvidence(
                        subQuestionIndex,
                        subQuestion,
                        List.of(),
                        List.of(),
                        new ArrayList<>(),
                        List.of(),
                        0,
                        0,
                        0,
                        failedSubQuestionLedger(retrievalPlan.isRerankRequested()),
                        retrievalFailureObservationPersistence(traceRecorder)
                    );
                }));
        }

        List<SubQuestionEvidence> evidenceList = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        int acceptedCount = (int) evidenceList.stream()
            .filter(item -> item.getSourceDocuments() != null && !item.getSourceDocuments().isEmpty())
            .count();
        log.info("RAG 检索完成: retrievalQuestion='{}', originalSubQuestionCount={}, acceptedSubQuestionCount={}, notes={}",
            retrievalQuestion,
            evidenceList.size(),
            acceptedCount,
            context.getRetrievalNotes());
        assignReferenceIds(evidenceList, retrievalPlan);
        context.setSubQuestionEvidenceList(evidenceList);
        return context;
    }

    private long resolveSubQuestionTimeoutMs(RetrievalPlan plan) {
        return Math.max(plan.getSubQuestionTimeoutMs(), 1L);
    }

    private EvidenceSelectionLedger failedSubQuestionLedger(boolean rerankRequested) {
        if (!rerankRequested) {
            return EvidenceSelectionLedger.empty(false);
        }
        return new EvidenceSelectionLedger(
            EvidenceLineage.empty(),
            new RerankExecution(
                true,
                false,
                RerankExecutionStatus.FAILED_BEFORE_REQUEST,
                RerankExecutionReason.CANCELLED,
                RerankFailureStage.PRE_REQUEST
            ),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private long resolveChannelTimeoutMs(RetrievalExecutionRequest request, String channelName) {
        return Math.max(request.requireChannel(channelName).timeoutMs(), 1L);
    }

    private CompletableFuture<SubQuestionEvidence> retrieveSingleSubQuestionAsync(RetrievalExecutionRequest request,
                                                                                   RetrievalPlan plan,
                                                                                   List<String> usedChannels,
                                                                                   List<String> notes,
                                                                                   ConversationTraceRecorder traceRecorder,
                                                                                   Long tenantId) {
        int subQuestionIndex = request.subQuestionIndex();
        String subQuestion = request.executionQuery();

        List<CompletableFuture<RetrievalChannelResult>> futures = enabledChannelImplementations(request).stream()
            .map(channel -> CompletableFuture.supplyAsync(
                    () -> TenantContext.callWith(tenantId, () -> channel.retrieve(request)),
                    executorService)
                .orTimeout(resolveChannelTimeoutMs(request, channel.channelName()), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {

                    Throwable rootCause = unwrapThrowable(throwable);
                    log.warn("检索通道失败: subQuestionIndex={}, subQuestion='{}', channel='{}', exceptionType={}, message={}",
                        subQuestionIndex,
                        subQuestion,
                        channel.channelName(),
                        rootCause == null ? "" : rootCause.getClass().getName(),
                        rootCause == null ? "" : rootCause.getMessage(),
                        throwable);
                    notes.add("子问题" + subQuestionIndex + "通道[" + channel.channelName() + "]检索失败或超时，已跳过该通道。");
                    return new RetrievalChannelResult(
                        channel.channelName(),
                        List.of(),
                        Map.of("status", "CHANNEL_ERROR"),
                        rootCause == null ? "" : StrUtil.blankToDefault(rootCause.getMessage(), rootCause.getClass().getName())
                    );
                }))
            .toList();
        if (futures.isEmpty()) {
            notes.add("子问题" + subQuestionIndex + "没有可用的检索通道。");
            return CompletableFuture.completedFuture(
                new SubQuestionEvidence(
                    subQuestionIndex,
                    subQuestion,
                    List.of(),
                    List.of(),
                    new ArrayList<>(),
                    List.of(),
                    0,
                    0,
                    0,
                    EvidenceSelectionLedger.empty(plan.isRerankRequested()),
                    emptyObservationPersistence(traceRecorder)
                )
            );
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
            .thenApplyAsync(ignored -> TenantContext.callWith(tenantId, () -> {
                List<RetrievalChannelResult> rawChannelResults = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(result -> result.getDocuments() != null)
                    .toList();
                rawChannelResults.forEach(result -> EvidenceCandidateIdentity.ensureAll(result.getDocuments()));
                List<RetrievalChannelResult> channelResults = rawChannelResults;
                List<SubQuestionChannelTrace> channelTraces = buildChannelTraces(rawChannelResults, channelResults, plan);

                channelResults.stream()
                    .filter(result -> !result.getDocuments().isEmpty())
                    .forEach(result -> markUsedChannel(usedChannels, result.getChannelName()));

                List<RetrievalDocument> fusionInputs = flattenChannelDocuments(channelResults);
                List<RetrievalDocument> mergedCandidates = hybridFusionService.fuse(channelResults, plan);
                IdentityTransition fusionTransition = IdentityTransitionRecorder.capture(
                    "fusion-" + subQuestionIndex,
                    IdentityTransitionType.FUSION_DEDUPLICATION,
                    IdentityTransitionStage.FUSION,
                    fusionInputs,
                    mergedCandidates
                );
                List<RetrievalDocument> elevatedParentCandidates = documentKnowledgeService.elevateToParentBlocks(
                    mergedCandidates,
                    properties.getParentEvidenceMaxChars()
                );
                List<RetrievalDocument> parentCandidates = contextExpansionPlanner.mergeElevatedParentCandidates(
                    mergedCandidates,
                    elevatedParentCandidates
                );
                EvidenceCandidateIdentity.ensureAll(parentCandidates);
                IdentityTransition parentTransition = IdentityTransitionRecorder.capture(
                    "parent-elevation-" + subQuestionIndex,
                    IdentityTransitionType.PARENT_ELEVATION,
                    IdentityTransitionStage.PARENT_ELEVATION,
                    mergedCandidates,
                    parentCandidates
                );
                List<RetrievalDocument> structureAnchorCandidates = contextExpansionPlanner.expandStructureAnchoredEvidence(parentCandidates, plan, notes, subQuestionIndex);
                List<RetrievalDocument> structureNavigationCandidates = contextExpansionPlanner.buildStructureNavigationContextCandidates(plan, notes, subQuestionIndex);
                List<RetrievalDocument> expandedCandidates = contextExpansionPlanner.mergeStructureAnchorCandidates(parentCandidates, structureAnchorCandidates);
                EvidenceCandidateIdentity.ensureAll(expandedCandidates);
                EvidenceCandidatePools candidatePools = contextExpansionPlanner.partitionCandidates(expandedCandidates, structureNavigationCandidates);
                List<RetrievalDocument> finalCandidates = new ArrayList<>(candidatePools.sourceDocuments());
                finalCandidates.addAll(candidatePools.contextDocuments());
                IdentityTransition contextTransition = IdentityTransitionRecorder.capture(
                    "context-expansion-" + subQuestionIndex,
                    IdentityTransitionType.CONTEXT_EXPANSION,
                    IdentityTransitionStage.CONTEXT_EXPANSION,
                    parentCandidates,
                    finalCandidates
                );

                SourceRankingWindow.Result rankingWindow = sourceRankingWindow.build(
                    candidatePools.sourceDocuments(),
                    plan.getRerankWindow()
                );
                RerankPipelineResult rerankResult = executeRerank(
                    subQuestionIndex,
                    subQuestion,
                    rankingWindow,
                    plan,
                    usedChannels,
                    notes
                );
                FinalEvidenceSelectionPolicy.SelectionResult selectionResult = rankingWindow.buildFailed()
                    ? new FinalEvidenceSelectionPolicy.SelectionResult(List.of(), List.of())
                    : finalEvidenceSelectionPolicy.select(rerankResult.candidates(), plan);
                projectPolicyDecisions(rerankResult.candidates(), selectionResult.decisions());
                List<RetrievalDocument> finalSourceDocuments = selectionResult.selectedDocuments();

                if (!selectionResult.decisions().isEmpty() && selectionResult.decisions().stream()
                    .allMatch(decision -> decision.reason() == FinalEvidenceDecision.Reason.FILTERED_NOT_APPLICABLE)) {
                    notes.add("子问题" + subQuestionIndex + "最终证据未明确支持当前目标对象，仅保留为相似但不适用证据。");
                }

                List<IdentityTransition> identityTransitions = java.util.stream.Stream.of(
                        fusionTransition,
                        parentTransition,
                        contextTransition
                    )
                    .filter(Objects::nonNull)
                    .toList();
                EvidenceSelectionLedger ledger = new EvidenceSelectionLedger(
                    new EvidenceLineage(
                        candidateIds(flattenChannelDocuments(rawChannelResults)),
                        candidateIds(flattenChannelDocuments(channelResults)),
                        lineageIdentities(mergedCandidates),
                        rankingWindow.candidates().stream()
                            .map(EvidenceSelectionLedger.SourceRankingWindowCandidate::lineageIdentity)
                            .toList(),
                        candidateIds(candidatePools.contextDocuments()),
                        candidateIds(candidatePools.sourceDocuments()),
                        candidateIds(rerankResult.candidates()),
                        finalSourceDocuments.stream()
                            .map(EvidenceIdentityResolver::citationIdentityValue)
                            .filter(identity -> !identity.isBlank())
                            .toList()
                    ),
                    rerankResult.execution(),
                    rankingWindow.decisions(),
                    rankingWindow.candidates(),
                    rerankResult.requestCandidates(),
                    rerankResult.resultCandidates(),
                    identityTransitions,
                    selectionResult.decisions()
                );

                appendGraphRagCanonicalNotes(subQuestionIndex, subQuestion, finalSourceDocuments, notes);

                notes.add("子问题" + subQuestionIndex + "检索完成："
                    + summarizeChannelResults(channelResults)
                    + "，source=" + finalSourceDocuments.size()
                    + "，context=" + candidatePools.contextDocuments().size());

                List<ObservationCandidate> observableCandidates = buildObservationCandidates(
                    rawChannelResults,
                    candidatePools.sourceDocuments()
                );
                ObservationPersistence observationPersistence = ObservationPersistence.notAttempted(
                    observableCandidates.size(),
                    ErrorType.NO_RECORDER
                );
                if (traceRecorder != null) {
                    try {
                        recordChannelObservations(traceRecorder, subQuestionIndex, subQuestion,
                            rawChannelResults, channelResults, channelTraces, finalSourceDocuments);
                    } catch (RuntimeException exception) {
                        log.warn("记录检索通道观测数据失败, subQuestionIndex={}", subQuestionIndex, exception);
                    }
                    observationPersistence = recordRetrievalResultObservations(
                        traceRecorder,
                        subQuestionIndex,
                        subQuestion,
                        observableCandidates,
                        channelResults,
                        mergedCandidates,
                        rerankResult.candidates(),
                        finalSourceDocuments
                    );
                }

                return new SubQuestionEvidence(
                    subQuestionIndex,
                    subQuestion,
                    finalSourceDocuments,
                    candidatePools.contextDocuments(),
                    new ArrayList<>(),
                    channelTraces,
                    mergedCandidates.size(),
                    parentCandidates.size(),
                    rerankResult.candidates().size(),
                    ledger,
                    observationPersistence
                );
            }), executorService);
    }

    private ObservationPersistence emptyObservationPersistence(ConversationTraceRecorder traceRecorder) {
        if (traceRecorder == null) {
            return ObservationPersistence.notAttempted(0, ErrorType.NO_RECORDER);
        }
        return traceRecorder.recordRetrievalResults(List.of());
    }

    private ObservationPersistence retrievalFailureObservationPersistence(ConversationTraceRecorder traceRecorder) {
        if (traceRecorder == null) {
            return ObservationPersistence.notAttempted(0, ErrorType.NO_RECORDER);
        }
        return ObservationPersistence.failed(0, ErrorType.RETRIEVAL_FAILED);
    }

    private void validateChannelImplementations(List<RetrievalExecutionRequest> requests) {
        Map<String, Long> implementationCounts = retrievalChannels.stream()
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.groupingBy(
                RetrievalChannel::channelName,
                LinkedHashMap::new,
                java.util.stream.Collectors.counting()
            ));
        Set<String> enabledNames = requests.stream()
            .flatMap(request -> request.enabledChannels().stream())
            .map(RetrievalExecutionRequest.ChannelSpec::channelName)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String channelName : enabledNames) {
            long count = implementationCounts.getOrDefault(channelName, 0L);
            if (count != 1L) {
                throw new IllegalStateException("Enabled retrieval channel must have exactly one implementation: "
                    + channelName + " (found " + count + ")");
            }
        }
    }

    private List<RetrievalChannel> enabledChannelImplementations(RetrievalExecutionRequest request) {
        Map<String, RetrievalChannel> implementations = retrievalChannels.stream()
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toMap(
                RetrievalChannel::channelName,
                channel -> channel,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        return request.enabledChannels().stream()
            .map(spec -> implementations.get(spec.channelName()))
            .toList();
    }

    private List<RetrievalDocument> flattenChannelDocuments(List<RetrievalChannelResult> channelResults) {
        if (channelResults == null) {
            return List.of();
        }
        return channelResults.stream()
            .filter(Objects::nonNull)
            .filter(result -> result.getDocuments() != null)
            .flatMap(result -> result.getDocuments().stream())
            .filter(Objects::nonNull)
            .toList();
    }

    private List<String> candidateIds(List<RetrievalDocument> candidates) {
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream()
            .filter(Objects::nonNull)
            .peek(EvidenceCandidateIdentity::ensure)
            .map(EvidenceCandidateIdentity::candidateId)
            .toList();
    }

    private List<String> lineageIdentities(List<RetrievalDocument> candidates) {
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream()
            .filter(Objects::nonNull)
            .peek(EvidenceCandidateIdentity::ensure)
            .map(EvidenceCandidateIdentity::lineageIdentity)
            .toList();
    }

    private void projectPolicyDecisions(List<RetrievalDocument> candidates, List<FinalEvidenceDecision> decisions) {
        if (candidates == null || decisions == null || candidates.size() != decisions.size()) {
            throw new IllegalStateException("Final policy decisions must match policy inputs by index");
        }
        for (int index = 0; index < candidates.size(); index++) {
            RetrievalDocument candidate = candidates.get(index);
            FinalEvidenceDecision decision = decisions.get(index);
            if (!EvidenceCandidateIdentity.candidateId(candidate).equals(decision.candidateId())) {
                throw new IllegalStateException("Final policy decision candidate mismatch");
            }
            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON, decision.reason().name());
            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.EVIDENCE_APPLICABILITY_STATUS,
                decision.applicabilityStatus());
            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.EVIDENCE_APPLICABILITY_REASON,
                decision.applicabilityReason());
        }
    }

    private boolean isMeaningfulMetadataValue(Object value) {
        if (value == null) {
            return false;
        }
        return !(value instanceof String text) || !text.isBlank();
    }

    private RetrievalIntent resolveRetrievalIntent(RetrievalPlan plan) {
        return plan == null || plan.getPrimaryIntent() == null ? RetrievalIntent.GENERAL : plan.getPrimaryIntent();
    }

    private Double numericMetadataValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean booleanMetadataValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private RerankPipelineResult executeRerank(int subQuestionIndex,
                                                String subQuestion,
                                                SourceRankingWindow.Result window,
                                                RetrievalPlan plan,
                                                List<String> usedChannels,
                                                List<String> notes) {
        boolean requested = plan.isRerankRequested();
        if (window.buildFailed()) {
            RerankExecution execution = requested
                ? new RerankExecution(true, false, RerankExecutionStatus.FAILED_BEFORE_REQUEST,
                    RerankExecutionReason.WINDOW_BUILD_FAILED, RerankFailureStage.WINDOW_BUILD)
                : new RerankExecution(false, false, RerankExecutionStatus.NOT_REQUESTED,
                    RerankExecutionReason.PLAN_DISABLED, RerankFailureStage.NONE);
            return new RerankPipelineResult(List.of(), execution, List.of(), List.of());
        }

        List<RetrievalDocument> candidates = window.includedDocuments();
        if (candidates.isEmpty()) {
            return new RerankPipelineResult(
                List.of(),
                EvidenceSelectionLedger.empty(requested).rerankExecution(),
                List.of(),
                List.of()
            );
        }
        if (!requested) {
            markPolicyRerankStatus(candidates, RerankStatus.NOT_REQUESTED, "");
            return new RerankPipelineResult(
                candidates,
                new RerankExecution(false, false, RerankExecutionStatus.NOT_REQUESTED,
                    RerankExecutionReason.PLAN_DISABLED, RerankFailureStage.NONE),
                List.of(),
                List.of()
            );
        }

        RagRerankService.TransportResult transport = ragRerankService == null
            ? new RagRerankService.TransportResult(
                candidates,
                false,
                false,
                RerankExecutionReason.CLIENT_INITIALIZATION_FAILED,
                RerankFailureStage.PRE_REQUEST,
                List.of(),
                List.of(),
                "RagRerankService is unavailable"
            )
            : ragRerankService.rerank(subQuestion, candidates);
        if (transport.success()) {
            markPolicyRerankStatus(candidates, RerankStatus.SUCCESS, "");
            markUsedChannel(usedChannels, RetrievalChannelEnum.RERANK.getName());
            return new RerankPipelineResult(
                candidates,
                new RerankExecution(true, true, RerankExecutionStatus.SUCCESS,
                    RerankExecutionReason.NONE, RerankFailureStage.NONE),
                transport.requestCandidates(),
                transport.resultCandidates()
            );
        }

        markPolicyRerankStatus(candidates, RerankStatus.FAILED, transport.error());
        if (transport.attempted()) {
            markUsedChannel(usedChannels, RetrievalChannelEnum.RERANK.getName());
        }
        log.warn("rerank 失败，保留 weighted hybrid Source 候选继续回答: subQuestionIndex={}, subQuestion='{}', candidateCount={}, attempted={}, reason={}, stage={}, error={}",
            subQuestionIndex,
            subQuestion,
            candidates.size(),
            transport.attempted(),
            transport.reason(),
            transport.failureStage(),
            transport.error());
        notes.add("子问题" + subQuestionIndex + " rerank 失败或超时，已保留融合 Source 候选继续回答。");
        return new RerankPipelineResult(
            candidates,
            new RerankExecution(
                true,
                transport.attempted(),
                transport.attempted()
                    ? RerankExecutionStatus.FAILED_AFTER_REQUEST
                    : RerankExecutionStatus.FAILED_BEFORE_REQUEST,
                transport.reason(),
                transport.failureStage()
            ),
            transport.requestCandidates(),
            transport.resultCandidates()
        );
    }

    private void markPolicyRerankStatus(List<RetrievalDocument> candidates, RerankStatus status, String error) {
        for (RetrievalDocument candidate : candidates) {
            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_STATUS, status.name());
            if (status != RerankStatus.SUCCESS) {
                candidate.getMetadata().remove(DocumentKnowledgeMetadataKeys.RERANK_SCORE);
                candidate.getMetadata().remove(DocumentKnowledgeMetadataKeys.RERANK_RANK);
            }
            if (error == null || error.isBlank()) {
                candidate.getMetadata().remove(DocumentKnowledgeMetadataKeys.RERANK_ERROR);
            }
            else {
                candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_ERROR, error);
            }
        }
    }

    private void appendGraphRagCanonicalNotes(int subQuestionIndex,
                                              String subQuestion,
                                              List<RetrievalDocument> finalDocuments,
                                              List<String> notes) {
        if (finalDocuments == null || finalDocuments.isEmpty()) {
            return;
        }
        LinkedHashMap<String, GraphRagCanonicalObservation> observationMap = new LinkedHashMap<>();
        for (int index = 0; index < finalDocuments.size(); index++) {
            RetrievalDocument document = finalDocuments.get(index);
            if (document == null || document.getMetadata() == null || !isGraphRagMetadata(document.getMetadata())) {
                continue;
            }
            String entityName = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME));
            String canonicalName = firstNonBlank(
                safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME)),
                entityName
            );
            String canonicalKey = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY));
            Integer entityCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT));
            Integer documentCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT));
            Integer relationGroupEvidenceCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT));
            Integer relationGroupDocumentCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT));
            String crossDocumentCommunityKey = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY));
            String communityTitle = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE));
            Integer communityEntityCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT));
            Integer communityRelationGroupCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT));
            Integer communityEvidenceCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT));
            Integer communityDocumentCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT));
            String graphPath = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH));
            String documentName = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME));

            if (canonicalName.isBlank() && canonicalKey.isBlank() && entityCount == null && documentCount == null
                && crossDocumentCommunityKey.isBlank()) {
                continue;
            }

            StringBuilder builder = new StringBuilder();
            if (!crossDocumentCommunityKey.isBlank()) {
                builder.append("community=").append(firstNonBlank(communityTitle, crossDocumentCommunityKey));
                builder.append("(entities=").append(communityEntityCount == null ? "-" : communityEntityCount)
                    .append(", relationGroups=").append(communityRelationGroupCount == null ? "-" : communityRelationGroupCount)
                    .append(", evidence=").append(communityEvidenceCount == null ? "-" : communityEvidenceCount)
                    .append(", docs=").append(communityDocumentCount == null ? "-" : communityDocumentCount)
                    .append(')');
                if (!canonicalName.isBlank() || !canonicalKey.isBlank()) {
                    builder.append(" canonical=").append(firstNonBlank(canonicalName, canonicalKey));
                }
            }
            else {
                builder.append(firstNonBlank(canonicalName, canonicalKey, "unknown"));
            }
            if (!entityName.isBlank() && !entityName.equals(canonicalName)) {
                builder.append("(命中实体=").append(entityName).append(')');
            }
            if (entityCount != null || documentCount != null) {
                builder.append("(entities=").append(entityCount == null ? "-" : entityCount)
                    .append(", docs=").append(documentCount == null ? "-" : documentCount).append(')');
            }
            if (relationGroupEvidenceCount != null || relationGroupDocumentCount != null) {
                builder.append("(relationGroup evidence=")
                    .append(relationGroupEvidenceCount == null ? "-" : relationGroupEvidenceCount)
                    .append(", docs=")
                    .append(relationGroupDocumentCount == null ? "-" : relationGroupDocumentCount)
                    .append(')');
            }
            if (!graphPath.isBlank()) {
                builder.append(" path=").append(graphPath);
            }
            if (!documentName.isBlank()) {
                builder.append(" doc=").append(documentName);
            }
            String text = builder.toString();
            observationMap.putIfAbsent(text, new GraphRagCanonicalObservation(
                text,
                graphRagObservationPriority(
                    document,
                    index,
                    entityCount,
                    documentCount,
                    relationGroupEvidenceCount,
                    relationGroupDocumentCount,
                    communityEvidenceCount,
                    communityDocumentCount
                ),
                index
            ));
        }
        if (observationMap.isEmpty()) {
            return;
        }
        List<String> limited = observationMap.values().stream()
            .sorted(Comparator.comparingDouble(GraphRagCanonicalObservation::priority).reversed()
                .thenComparingInt(GraphRagCanonicalObservation::originalIndex))
            .map(GraphRagCanonicalObservation::text)
            .limit(4)
            .toList();
        String summary = String.join("；", limited);
        notes.add("子问题" + subQuestionIndex + " GraphRAG canonical 观测：" + summary);
        log.info("GraphRAG canonical 观测: subQuestionIndex={}, subQuestion='{}', observations={}",
            subQuestionIndex,
            subQuestion,
            summary);
    }

    private double graphRagObservationPriority(RetrievalDocument document,
                                               int originalIndex,
                                               Integer entityCount,
                                               Integer documentCount,
                                               Integer relationGroupEvidenceCount,
                                               Integer relationGroupDocumentCount,
                                               Integer communityEvidenceCount,
                                               Integer communityDocumentCount) {
        double priority = Math.max(0D, finalDocumentScore(document));
        if (communityDocumentCount != null && communityDocumentCount > 1) {
            priority += 0.80D + Math.min(0.50D, communityDocumentCount * 0.10D);
        }
        if (communityEvidenceCount != null && communityEvidenceCount > 1) {
            priority += 0.35D + Math.min(0.35D, communityEvidenceCount * 0.04D);
        }
        if (relationGroupDocumentCount != null && relationGroupDocumentCount > 1) {
            priority += 0.60D + Math.min(0.40D, relationGroupDocumentCount * 0.08D);
        }
        if (relationGroupEvidenceCount != null && relationGroupEvidenceCount > 1) {
            priority += 0.30D + Math.min(0.30D, relationGroupEvidenceCount * 0.04D);
        }
        if (documentCount != null && documentCount > 1) {
            priority += 0.25D + Math.min(0.25D, documentCount * 0.04D);
        }
        if (entityCount != null && entityCount > 1) {
            priority += 0.10D + Math.min(0.15D, entityCount * 0.02D);
        }
        if (document != null && document.getMetadata() != null) {
            if (document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null) {
                priority += 0.08D;
            }
            if (document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID) != null) {
                priority += 0.06D;
            }
        }
        return priority - originalIndex * 0.0001D;
    }

    private double finalDocumentScore(RetrievalDocument document) {
        if (document == null) {
            return 0D;
        }
        Double score = resolveScore(document);
        if (score != null) {
            return score;
        }
        return document.getScore() == null ? 0D : document.getScore();
    }

    private boolean isGraphRagMetadata(Map<String, Object> metadata) {
        String channel = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL));
        String sourceType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        return RetrievalChannelEnum.GRAPH_RAG.getName().equals(channel)
            || "GRAPH_RAG".equalsIgnoreCase(sourceType)
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID) != null
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY))
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID) != null
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String joinSections(String... sections) {
        if (sections == null || sections.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String section : sections) {
            if (section == null || section.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(section);
        }
        return builder.toString();
    }

    private record GraphRagCanonicalObservation(String text, double priority, int originalIndex) {
    }

    private Integer integerMetadataValue(Object value) {
        Double number = numericMetadataValue(value);
        return number == null ? null : number.intValue();
    }

    private void assignReferenceIds(List<SubQuestionEvidence> evidenceList, RetrievalPlan plan) {
        final int[] referenceNumber = {1};
        Map<String, String> assignedIds = new LinkedHashMap<>();
        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = knowledgeBaseReferenceDescriptorMap(evidenceList, plan);
        for (SubQuestionEvidence evidence : evidenceList) {
            List<SearchReference> references = new ArrayList<>();
            for (RetrievalDocument document : evidence.getSourceDocuments()) {
                enrichKnowledgeBaseReferenceMetadata(document, descriptorMap);

                SearchReference reference = SearchReferenceMapper.fromDocument(
                    document,
                    evidence.getSubQuestionIndex(),
                    evidence.getSubQuestion(),
                    0
                );
                String uniqueKey = reference.uniqueKey();

                String assignedId = assignedIds.computeIfAbsent(uniqueKey, ignored -> String.valueOf(referenceNumber[0]++));
                reference.setReferenceId(assignedId);
                references.add(reference);
            }
            if (evidence.getContextDocuments() != null) {
                evidence.getContextDocuments().forEach(document -> enrichKnowledgeBaseReferenceMetadata(document, descriptorMap));
            }
            evidence.setReferences(references);
        }
    }

    private Map<Long, KnowledgeDocumentDescriptor> knowledgeBaseReferenceDescriptorMap(List<SubQuestionEvidence> evidenceList,
                                                                                       RetrievalPlan plan) {
        Set<Long> documentIds = new LinkedHashSet<>();
        if (evidenceList != null) {
            for (SubQuestionEvidence evidence : evidenceList) {
                if (evidence == null) {
                    continue;
                }
                List<RetrievalDocument> documents = new ArrayList<>();
                if (evidence.getSourceDocuments() != null) {
                    documents.addAll(evidence.getSourceDocuments());
                }
                if (evidence.getContextDocuments() != null) {
                    documents.addAll(evidence.getContextDocuments());
                }
                for (RetrievalDocument document : documents) {
                    Long documentId = metadataLong(document, DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
                    if (documentId != null && needsKnowledgeBaseReferenceFallback(document)) {
                        documentIds.add(documentId);
                    }
                }
            }
        }
        if (documentIds.isEmpty()) {
            return Map.of();
        }

        List<KnowledgeDocumentDescriptor> descriptors = plan != null
            && plan.getKnowledgeBaseIds() != null
            && !plan.getKnowledgeBaseIds().isEmpty()
            ? documentKnowledgeService.listRetrievableDocumentsByKnowledgeBaseIds(plan.getKnowledgeBaseIds())
            : documentKnowledgeService.listRetrievableDocuments();
        if (descriptors == null || descriptors.isEmpty()) {
            return Map.of();
        }

        return descriptors.stream()
            .filter(descriptor -> descriptor.getDocumentId() != null && documentIds.contains(descriptor.getDocumentId()))
            .collect(java.util.stream.Collectors.toMap(
                KnowledgeDocumentDescriptor::getDocumentId,
                descriptor -> descriptor,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private boolean needsKnowledgeBaseReferenceFallback(RetrievalDocument document) {
        if (document == null || document.getMetadata() == null) {
            return false;
        }
        return !isMeaningfulMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID))
            || !isMeaningfulMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME));
    }

    private void enrichKnowledgeBaseReferenceMetadata(RetrievalDocument document,
                                                      Map<Long, KnowledgeDocumentDescriptor> descriptorMap) {
        if (document == null || document.getMetadata() == null || descriptorMap == null || descriptorMap.isEmpty()) {
            return;
        }
        Long documentId = metadataLong(document, DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
        if (documentId == null) {
            return;
        }
        KnowledgeDocumentDescriptor descriptor = descriptorMap.get(documentId);
        if (descriptor == null) {
            return;
        }
        Map<String, Object> metadata = document.getMetadata();
        if (!isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME))
            && isMeaningfulMetadataValue(descriptor.getDocumentName())) {
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, descriptor.getDocumentName());
        }
        if (!isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID))
            && descriptor.getKnowledgeBaseId() != null) {
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, descriptor.getKnowledgeBaseId());
        }
        if (!isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME))
            && isMeaningfulMetadataValue(descriptor.getKnowledgeBaseName())) {
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, descriptor.getKnowledgeBaseName());
        }
    }

    private Long metadataLong(RetrievalDocument document, String key) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        return asLong(document.getMetadata().get(key));
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Double resolveScore(RetrievalDocument document) {
        if (document == null) {
            return null;
        }

        Object metadataScore = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        if (metadataScore instanceof Number number) {
            return number.doubleValue();
        }
        return document.getScore();
    }

    private void markUsedChannel(List<String> usedChannels, String channel) {

        if (!usedChannels.contains(channel)) {
            usedChannels.add(channel);
        }
    }

    private String summarizeChannelResults(List<RetrievalChannelResult> channelResults) {
        if (channelResults.isEmpty()) {
            return "没有启用任何检索通道";
        }
        return channelResults.stream()
            .map(result -> result.getChannelName() + "=" + result.getDocuments().size())
            .reduce((left, right) -> left + "，" + right)
            .orElse("没有检索结果");
    }

    private List<SubQuestionChannelTrace> buildChannelTraces(List<RetrievalChannelResult> rawResults,
                                                             List<RetrievalChannelResult> filteredResults,
                                                             RetrievalPlan plan) {
        if ((rawResults == null || rawResults.isEmpty()) && (filteredResults == null || filteredResults.isEmpty())) {
            return List.of();
        }
        Map<String, Integer> rawMap = new LinkedHashMap<>();
        Map<String, Integer> filteredMap = new LinkedHashMap<>();
        if (rawResults != null) {
            rawResults.forEach(result -> rawMap.put(result.getChannelName(), result.getDocuments() == null ? 0 : result.getDocuments().size()));
        }
        if (filteredResults != null) {
            filteredResults.forEach(result -> filteredMap.put(result.getChannelName(), result.getDocuments() == null ? 0 : result.getDocuments().size()));
        }
        LinkedHashSet<String> channelNames = new LinkedHashSet<>();
        channelNames.addAll(rawMap.keySet());
        channelNames.addAll(filteredMap.keySet());
        List<SubQuestionChannelTrace> traces = new ArrayList<>(channelNames.size());
        RetrievalIntent retrievalIntent = resolveRetrievalIntent(plan);
        for (String channelName : channelNames) {
            traces.add(new SubQuestionChannelTrace(
                channelName,
                retrievalIntent.name(),
                hybridFusionService.resolveChannelWeight(channelName, plan),
                rawMap.getOrDefault(channelName, 0),
                filteredMap.getOrDefault(channelName, 0)
            ));
        }
        return traces;
    }

    private void recordChannelObservations(ConversationTraceRecorder traceRecorder,
                                           int subQuestionIndex,
                                           String subQuestion,
                                           List<RetrievalChannelResult> rawResults,
                                           List<RetrievalChannelResult> filteredResults,
                                           List<SubQuestionChannelTrace> channelTraces,
                                           List<RetrievalDocument> finalDocuments) {
        if (rawResults == null || rawResults.isEmpty()) {
            return;
        }

        List<ChannelExecutionView> executions = new ArrayList<>();
        for (RetrievalChannelResult rawResult : rawResults) {
            String channelName = rawResult.getChannelName();
            int recalledCount = rawResult.getDocuments() == null ? 0 : rawResult.getDocuments().size();

            RetrievalChannelResult filteredResult = filteredResults == null ? null :
                filteredResults.stream().filter(r -> channelName.equals(r.getChannelName())).findFirst().orElse(null);
            int acceptedCount = filteredResult == null || filteredResult.getDocuments() == null ? 0 : filteredResult.getDocuments().size();
            int finalSelectedCount = countSelectedChannelDocuments(filteredResult, finalDocuments);

            ChannelExecutionView execution = new ChannelExecutionView();
            execution.setId(traceRecorder.exchangeId());
            execution.setTraceId(traceRecorder.traceId());
            execution.setSubQuestionIndex(subQuestionIndex);
            execution.setSubQuestion(subQuestion);
            execution.setChannelType(channelName);
            execution.setExecutionState(StrUtil.isBlank(rawResult.getErrorMessage()) ? 1 : 2);
            execution.setRecalledCount(recalledCount);
            execution.setAcceptedCount(acceptedCount);
            execution.setFinalSelectedCount(finalSelectedCount);
            execution.setConfigSnapshot(rawResult.getConfigSnapshot());
            execution.setErrorMessage(rawResult.getErrorMessage());

            if (rawResult.getDocuments() != null && !rawResult.getDocuments().isEmpty()) {
                List<Double> scores = rawResult.getDocuments().stream()
                    .map(doc -> {
                        Object scoreObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
                        if (scoreObj instanceof Number) {
                            return ((Number) scoreObj).doubleValue();
                        }
                        return 0.0;
                    })
                    .filter(score -> score > 0)
                    .toList();

                if (!scores.isEmpty()) {
                    execution.setAvgScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                    execution.setMaxScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
                    execution.setMinScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
                }
            }

            executions.add(execution);
        }

        traceRecorder.recordChannelExecutions(executions);
    }

    private ObservationPersistence recordRetrievalResultObservations(ConversationTraceRecorder traceRecorder,
                                                                     int subQuestionIndex,
                                                                     String subQuestion,
                                                                     List<ObservationCandidate> observationCandidates,
                                                                     List<RetrievalChannelResult> filteredResults,
                                                                     List<RetrievalDocument> mergedCandidates,
                                                                     List<RetrievalDocument> rerankedCandidates,
                                                                     List<RetrievalDocument> finalDocuments) {
        int expectedCandidateCount = observationCandidates == null ? 0 : observationCandidates.size();
        try {
            return projectAndPersistRetrievalResultObservations(
                traceRecorder,
                subQuestionIndex,
                subQuestion,
                observationCandidates,
                filteredResults,
                mergedCandidates,
                rerankedCandidates,
                finalDocuments,
                expectedCandidateCount
            );
        } catch (RuntimeException exception) {
            log.warn("投影检索候选观测数据失败, subQuestionIndex={}, expectedCandidateCount={}",
                subQuestionIndex, expectedCandidateCount, exception);
            return ObservationPersistence.failed(
                expectedCandidateCount,
                ErrorType.OBSERVATION_PROJECTION_ERROR
            );
        }
    }

    private ObservationPersistence projectAndPersistRetrievalResultObservations(
        ConversationTraceRecorder traceRecorder,
        int subQuestionIndex,
        String subQuestion,
        List<ObservationCandidate> observationCandidates,
        List<RetrievalChannelResult> filteredResults,
        List<RetrievalDocument> mergedCandidates,
        List<RetrievalDocument> rerankedCandidates,
        List<RetrievalDocument> finalDocuments,
        int expectedCandidateCount) {
        List<RetrievalResultView> results = new ArrayList<>();
        Map<String, Integer> finalRankMap = new LinkedHashMap<>();
        Map<String, RetrievalDocument> finalDocumentMap = new LinkedHashMap<>();
        Map<String, RetrievalDocument> mergedCandidateMap = new LinkedHashMap<>();
        Map<String, RetrievalDocument> rerankedCandidateMap = new LinkedHashMap<>();
        if (finalDocuments != null) {
            for (int i = 0; i < finalDocuments.size(); i++) {
                RetrievalDocument finalDocument = finalDocuments.get(i);
                String docId = finalDocument.getId();
                if (docId != null) {
                    finalRankMap.put(docId, i + 1);
                    finalDocumentMap.put(docId, finalDocument);
                }
                String citationIdentity = EvidenceIdentityResolver.citationIdentityValue(finalDocument);
                if (!citationIdentity.isBlank()) {
                    finalRankMap.put(citationIdentity, i + 1);
                    finalDocumentMap.put(citationIdentity, finalDocument);
                }
            }
        }
        if (mergedCandidates != null) {
            for (RetrievalDocument document : mergedCandidates) {
                if (document.getId() != null) {
                    mergedCandidateMap.put(document.getId(), document);
                }
            }
        }
        if (rerankedCandidates != null) {
            for (RetrievalDocument document : rerankedCandidates) {
                if (document.getId() != null) {
                    rerankedCandidateMap.put(document.getId(), document);
                }
            }
        }

        if (observationCandidates != null) {
            for (ObservationCandidate observationCandidate : observationCandidates) {
                RetrievalDocument doc = observationCandidate.document();
                String channelName = observationCandidate.channelName();
                RetrievalDocument mergedDoc = findMatchingDocument(doc, mergedCandidates, mergedCandidateMap);
                EvidenceCandidateNormalizer.enrichIdentity(doc);
                EvidenceCandidateNormalizer.enrichIdentity(mergedDoc);
                Map<String, Object> scoreMetadata = mergedDoc == null ? doc.getMetadata() : mergedDoc.getMetadata();
                RetrievalDocument rerankedDoc = findMatchingDocument(doc, rerankedCandidates, rerankedCandidateMap);
                EvidenceCandidateNormalizer.enrichIdentity(rerankedDoc);
                Map<String, Object> rerankMetadata = rerankedDoc == null ? scoreMetadata : rerankedDoc.getMetadata();
                RetrievalResultView view = new RetrievalResultView();
                view.setId(traceRecorder.exchangeId());
                view.setTraceId(traceRecorder.traceId());
                view.setSubQuestionIndex(subQuestionIndex);
                view.setSubQuestion(subQuestion);
                view.setChannelType(channelName);
                view.setChannelRank(observationCandidate.channelRank());
                // O9 稳定候选身份：优先 citation 身份，其次 context 身份，最后回退 docId，
                // 让同一候选在 channel/fusion/rerank/final 各观测行之间可追踪。
                view.setCandidateId(EvidenceCandidateNormalizer.resolveCandidateId(doc));

                Double originalScore = resolveTraceOriginalScore(doc, channelName);
                if (originalScore != null) {
                    view.setOriginalScore(BigDecimal.valueOf(originalScore));
                }

                Object rrfScoreObj = scoreMetadata.get(DocumentKnowledgeMetadataKeys.RRF_SCORE);
                if (rrfScoreObj instanceof Number) {
                    view.setRrfScore(BigDecimal.valueOf(((Number) rrfScoreObj).doubleValue()));
                }

                Object hybridScoreObj = scoreMetadata.get(DocumentKnowledgeMetadataKeys.HYBRID_SCORE);
                if (hybridScoreObj instanceof Number) {
                    view.setHybridScore(BigDecimal.valueOf(((Number) hybridScoreObj).doubleValue()));
                }

                Object metadataBoostObj = scoreMetadata.get(DocumentKnowledgeMetadataKeys.METADATA_BOOST);
                if (metadataBoostObj instanceof Number) {
                    view.setMetadataBoost(BigDecimal.valueOf(((Number) metadataBoostObj).doubleValue()));
                }

                Object vectorScoreObj = scoreMetadata.get(DocumentKnowledgeMetadataKeys.VECTOR_SCORE);
                if (vectorScoreObj instanceof Number) {
                    view.setVectorScore(BigDecimal.valueOf(((Number) vectorScoreObj).doubleValue()));
                }

                Object keywordScoreObj = scoreMetadata.get(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE);
                if (keywordScoreObj instanceof Number) {
                    view.setKeywordScore(BigDecimal.valueOf(((Number) keywordScoreObj).doubleValue()));
                }

                Object rerankScoreObj = rerankMetadata.get(DocumentKnowledgeMetadataKeys.RERANK_SCORE);
                if (rerankScoreObj instanceof Number) {
                    view.setRerankScore(BigDecimal.valueOf(((Number) rerankScoreObj).doubleValue()));
                }

                Object docIdObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
                if (docIdObj != null) {
                    view.setDocumentId(Long.parseLong(String.valueOf(docIdObj)));
                }

                Object docNameObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME);
                if (docNameObj != null) {
                    view.setDocumentName(String.valueOf(docNameObj));
                }

                Object chunkIdObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_ID);
                if (chunkIdObj != null) {
                    view.setChunkId(Long.parseLong(String.valueOf(chunkIdObj)));
                }

                Object chunkTypeObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE);
                if (chunkTypeObj != null) {
                    view.setChunkType(String.valueOf(chunkTypeObj));
                }

                Object chunkNoObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO);
                if (chunkNoObj != null) {
                    view.setChunkNo(Integer.parseInt(String.valueOf(chunkNoObj)));
                }

                Object parentBlockIdObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID);
                if (parentBlockIdObj != null) {
                    view.setParentBlockId(Long.parseLong(String.valueOf(parentBlockIdObj)));
                }

                Object parentBlockNoObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO);
                if (parentBlockNoObj != null) {
                    view.setParentBlockNo(Integer.parseInt(String.valueOf(parentBlockNoObj)));
                }

                Object sectionPathObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SECTION_PATH);
                if (sectionPathObj != null) {
                    view.setSectionPath(String.valueOf(sectionPathObj));
                }
                view.setContextIdentity(safeText(doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CONTEXT_IDENTITY)));
                view.setCitationIdentity(safeText(doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CITATION_IDENTITY)));
                view.setCitationEvidenceType(safeText(doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CITATION_EVIDENCE_TYPE)));
                view.setContextOnly(booleanMetadataValue(doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CONTEXT_ONLY)));
                view.setSourceEvidenceResolved(booleanMetadataValue(doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SOURCE_EVIDENCE_RESOLVED)));
                view.setRankFeature(rankFeatureService.resolveObservationRankFeature(scoreMetadata, rerankMetadata));

                String content = doc.getText();
                if (content != null && !content.isEmpty()) {
                    view.setChunkTextPreview(content.length() > 500 ? content.substring(0, 500) : content);
                    view.setChunkCharCount(content.length());
                }

                boolean passedGate = observationCandidate.introducedAfterChannelGate()
                    || filteredResults != null && filteredResults.stream()
                        .anyMatch(fr -> channelName.equals(fr.getChannelName()) &&
                            fr.getDocuments() != null &&
                            fr.getDocuments().stream().anyMatch(d -> sameEvidenceIdentity(d, doc)));
                view.setGatePassed(passedGate);

                Integer finalRank = resolveFinalRank(doc, finalDocuments, finalRankMap);
                boolean isSelected = finalRank != null;
                view.setSelected(isSelected);

                // O9 观测：命中候选只写 selectionReason，被过滤候选只写 filteredReason，两者不再混用同一字段。
                //
                // 被过滤的候选分两类，之前只写了 gate 未通过的那一类，导致"通过 gate 但被后段淘汰"
                // 的候选两个字段都为空（实测 27 个候选里 18 个无原因），O9 账本无法回答
                // 「这段内容为什么没被引用」，违反验证规则 §5「每个 ranking-window decision 有唯一 owner 和 reason」。
                if (isSelected) {
                    view.setFinalRank(finalRank);
                    RetrievalDocument finalDocument = findMatchingDocument(doc, finalDocuments, finalDocumentMap);
                    view.setSelectionReason(resolveSelectedReason(finalDocument));
                } else if (!passedGate) {
                    view.setFilteredReason(resolveGateFilteredReason(channelName));
                } else {
                    view.setFilteredReason(resolvePostGateFilteredReason(doc));
                }

                results.add(view);
            }
        }

        if (results.size() != expectedCandidateCount) {
            throw new IllegalStateException("Retrieval observation projection must conserve observable candidate count");
        }
        return traceRecorder.recordRetrievalResults(results);
    }

    private List<ObservationCandidate> buildObservationCandidates(List<RetrievalChannelResult> rawResults,
                                                                  List<RetrievalDocument> sourceCandidates) {
        List<ObservationCandidate> observations = new ArrayList<>();
        List<RetrievalDocument> representedDocuments = new ArrayList<>();
        if (rawResults != null) {
            for (RetrievalChannelResult rawResult : rawResults) {
                if (rawResult == null || rawResult.getDocuments() == null) {
                    continue;
                }
                for (int index = 0; index < rawResult.getDocuments().size(); index++) {
                    RetrievalDocument document = rawResult.getDocuments().get(index);
                    observations.add(new ObservationCandidate(
                        document,
                        rawResult.getChannelName(),
                        index + 1,
                        false
                    ));
                    if (document != null) {
                        representedDocuments.add(document);
                    }
                }
            }
        }
        if (sourceCandidates == null || sourceCandidates.isEmpty()) {
            return observations;
        }
        // Context expansion can introduce citation-capable Source Evidence after channel retrieval.
        // The caller supplies only the Source pool here, so Context Only remains outside O9.
        Map<String, Integer> derivedChannelRanks = new LinkedHashMap<>();
        for (RetrievalDocument sourceCandidate : sourceCandidates) {
            if (sourceCandidate == null || representedDocuments.stream()
                .anyMatch(document -> sameEvidenceIdentity(document, sourceCandidate))) {
                continue;
            }
            String channelName = safeText(sourceCandidate.getMetadata()
                .get(DocumentKnowledgeMetadataKeys.CHANNEL));
            if (channelName.isBlank()) {
                channelName = "context-expansion";
            }
            int channelRank = derivedChannelRanks.merge(channelName, 1, Integer::sum);
            observations.add(new ObservationCandidate(sourceCandidate, channelName, channelRank, true));
            representedDocuments.add(sourceCandidate);
        }
        return observations;
    }

    private record ObservationCandidate(RetrievalDocument document,
                                        String channelName,
                                        int channelRank,
                                        boolean introducedAfterChannelGate) {
    }

    private String resolveSelectedReason(RetrievalDocument finalDocument) {
        if (finalDocument == null || finalDocument.getMetadata() == null) {
            return "";
        }
        return safeText(finalDocument.getMetadata().get(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON));
    }

    private String resolveGateFilteredReason(String channelName) {
        if (RetrievalChannelEnum.VECTOR.getName().equals(channelName)) {
            return FILTERED_BY_VECTOR_GATE;
        }
        if (RetrievalChannelEnum.KEYWORD.getName().equals(channelName)) {
            return FILTERED_BY_KEYWORD_RELATIVE_SCORE;
        }
        return FILTERED_BY_CHANNEL_GATE;
    }

    /**
     * 通过 channel gate 但未被最终选中的原因。
     *
     * <p>进入排序窗口的候选会由 {@code projectPolicyDecisions} 在 metadata 上留下最终策略原因，
     * 直接采用它；没有该原因说明候选在窗口处就被截断（gate 与最终策略之间只有窗口一个阶段），
     * 因此写入窗口截断原因。这样每个候选都有唯一且准确的原因。</p>
     */
    private String resolvePostGateFilteredReason(RetrievalDocument candidate) {
        String policyReason = candidate == null
            ? null
            : safeText(candidate.getMetadata().get(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON));
        return policyReason == null || policyReason.isBlank() ? FILTERED_BY_SOURCE_RANKING_WINDOW : policyReason;
    }

    private RetrievalDocument findMatchingDocument(RetrievalDocument candidate,
                                          List<RetrievalDocument> documents,
                                          Map<String, RetrievalDocument> byId) {
        if (candidate == null || documents == null || documents.isEmpty()) {
            return null;
        }
        if (candidate.getId() != null && byId != null && byId.containsKey(candidate.getId())) {
            return byId.get(candidate.getId());
        }
        String citationIdentity = EvidenceIdentityResolver.citationIdentityValue(candidate);
        if (!citationIdentity.isBlank() && byId != null && byId.containsKey(citationIdentity)) {
            return byId.get(citationIdentity);
        }
        return documents.stream()
            .filter(document -> sameEvidenceIdentity(candidate, document))
            .findFirst()
            .orElse(null);
    }

    private int countSelectedChannelDocuments(RetrievalChannelResult filteredResult, List<RetrievalDocument> finalDocuments) {
        if (filteredResult == null || filteredResult.getDocuments() == null || filteredResult.getDocuments().isEmpty()
            || finalDocuments == null || finalDocuments.isEmpty()) {
            return 0;
        }
        return (int) filteredResult.getDocuments().stream()
            .filter(candidate -> resolveFinalRank(candidate, finalDocuments, Map.of()) != null)
            .count();
    }

    private Integer resolveFinalRank(RetrievalDocument candidate,
                                     List<RetrievalDocument> finalDocuments,
                                     Map<String, Integer> finalRankMap) {
        if (candidate == null || finalDocuments == null || finalDocuments.isEmpty()) {
            return null;
        }
        if (candidate.getId() != null && finalRankMap != null && finalRankMap.containsKey(candidate.getId())) {
            return finalRankMap.get(candidate.getId());
        }
        String citationIdentity = EvidenceIdentityResolver.citationIdentityValue(candidate);
        if (!citationIdentity.isBlank() && finalRankMap != null && finalRankMap.containsKey(citationIdentity)) {
            return finalRankMap.get(citationIdentity);
        }
        for (int index = 0; index < finalDocuments.size(); index++) {
            if (sameEvidenceIdentity(candidate, finalDocuments.get(index))) {
                return index + 1;
            }
        }
        return null;
    }

    private boolean sameEvidenceIdentity(RetrievalDocument left, RetrievalDocument right) {
        return EvidenceCandidateNormalizer.sameEvidenceIdentity(left, right);
    }

    private Long metadataLong(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Double resolveTraceOriginalScore(RetrievalDocument document, String channelName) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Object scoreObj = null;
        if (RetrievalChannelEnum.VECTOR.getName().equals(channelName)) {
            scoreObj = document.getMetadata().get(DocumentKnowledgeMetadataKeys.VECTOR_SCORE);
        }
        if (RetrievalChannelEnum.KEYWORD.getName().equals(channelName)) {
            scoreObj = document.getMetadata().get(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE);
        }
        if (RetrievalChannelEnum.TABLE.getName().equals(channelName)) {
            scoreObj = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        }
        if (!(scoreObj instanceof Number)) {
            scoreObj = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        }
        return scoreObj instanceof Number number ? number.doubleValue() : null;
    }

    private record RerankPipelineResult(
        List<RetrievalDocument> candidates,
        RerankExecution execution,
        List<RerankRequestCandidate> requestCandidates,
        List<RerankResultCandidate> resultCandidates
    ) {

        private RerankPipelineResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            requestCandidates = requestCandidates == null ? List.of() : List.copyOf(requestCandidates);
            resultCandidates = resultCandidates == null ? List.of() : List.copyOf(resultCandidates);
            Objects.requireNonNull(execution, "execution");
        }
    }

    private Throwable unwrapThrowable(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current.getCause() != null
            && current.getCause() != current
            && (current instanceof CompletionException
            || current instanceof ExecutionException
            || current instanceof TimeoutException)) {
            current = current.getCause();
        }
        return current;
    }
}
