package org.smartledge.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.ExecutionMode;
import org.smartledge.ai.chatagent.rag.model.ObservationPersistence;
import org.smartledge.ai.chatagent.rag.model.PromptRenderedSourceEvidence;
import org.smartledge.ai.chatagent.rag.model.RagPromptAssemblyResult;
import org.smartledge.ai.chatagent.rag.model.RagRetrievalContext;
import org.smartledge.ai.chatagent.rag.model.RetrievalExecutionRequest;
import org.smartledge.ai.chatagent.rag.model.SubQuestionEvidence;
import org.smartledge.ai.chatagent.rag.service.RagPromptAssemblyService;
import org.smartledge.ai.chatagent.rag.service.RagRetrievalEngine;
import org.smartledge.ai.chatagent.rag.support.EvidenceCandidateNormalizer;
import org.smartledge.ai.chatagent.rag.support.EvidenceIdentityResolver;
import org.smartledge.ai.chatagent.rag.support.EvidenceQualityFeatures;
import org.smartledge.ai.chatagent.rag.support.ExecutorEventSupport;
import org.smartledge.ai.chatagent.service.ConversationTraceRecorder;
import org.smartledge.ai.chatagent.service.ObservedChatModelService;
import org.smartledge.ai.chatagent.service.TaskInfo;
import org.smartledge.ai.chatagent.support.StreamEventWriter;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.smartledge.database.tenant.TenantContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: 知识问答执行器
 * @author: Song
 **/

@Component
public class RagChatExecutor implements ConversationExecutor {

    private final RagRetrievalEngine ragRetrievalEngine;
    private final RagPromptAssemblyService ragPromptAssemblyService;
    private final StreamEventWriter streamEventWriter;
    private final ObservedChatModelService observedChatModelService;

    public RagChatExecutor(RagRetrievalEngine ragRetrievalEngine,
                           RagPromptAssemblyService ragPromptAssemblyService,
                           StreamEventWriter streamEventWriter,
                           ObservedChatModelService observedChatModelService) {
        this.ragRetrievalEngine = ragRetrievalEngine;
        this.ragPromptAssemblyService = ragPromptAssemblyService;
        this.streamEventWriter = streamEventWriter;
        this.observedChatModelService = observedChatModelService;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.RETRIEVAL;
    }

    @Override
    public Flux<String> execute(TaskInfo taskInfo) {
        ConversationExecutionPlan plan = taskInfo.executionPlan();

        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "正在根据问题规划知识检索范围。");

        ConversationTraceRecorder.StageHandle retrieveStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                ConversationTraceStageCode.RAG_RETRIEVE,
                mode().name(),
                "正在执行多通道混合检索。",
                retrievalPlanSnapshot(plan)
            );

        return Mono.fromCallable(() -> TenantContext.callWith(
                taskInfo.tenantId(),
                () -> ragRetrievalEngine.retrieve(plan, taskInfo.traceRecorder())
            ))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(error -> {
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(retrieveStage, "RAG 检索失败。", error.getMessage(), retrievalPlanSnapshot(plan));
                }
            })
            .doOnSuccess(context -> {
                if (taskInfo.traceRecorder() != null && context != null) {
                    taskInfo.traceRecorder().completeStage(retrieveStage, "RAG 检索完成。", Map.ofEntries(
                        Map.entry("retrievalQuestion", StrUtil.blankToDefault(context.getRetrievalQuestion(), "")),
                        Map.entry("retrievalPlan", plan == null || plan.getRetrievalPlan() == null ? Map.of() : plan.getRetrievalPlan()),
                        Map.entry("executionRequests", context.getExecutionRequests() == null ? List.of() : context.getExecutionRequests()),
                        Map.entry("requestReconciliations", context.getRequestReconciliations() == null ? List.of() : context.getRequestReconciliations()),
                        Map.entry("usedChannels", context.getUsedChannels() == null ? List.of() : context.getUsedChannels()),
                        Map.entry("retrievalNotes", context.getRetrievalNotes() == null ? List.of() : context.getRetrievalNotes()),
                        Map.entry("referenceCount", context.flattenReferences().size()),
                        Map.entry("subQuestionCount", context.getSubQuestionEvidenceList() == null ? 0 : context.getSubQuestionEvidenceList().size()),
                        Map.entry("subQuestions", context.getSubQuestionEvidenceList() == null
                            ? List.of()
                            : context.getSubQuestionEvidenceList().stream().map(this::subQuestionTraceItem).toList()),
                        Map.entry("references", referenceTraceItems(context.flattenReferences()))
                    ));
                }
            })
            .flatMapMany(context -> streamFromRetrievalContext(taskInfo, plan, context));
    }

    private Map<String, Object> retrievalPlanSnapshot(ConversationExecutionPlan plan) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("retrievalPlan", plan == null ? null : plan.getRetrievalPlan());
        return snapshot;
    }

    private Map<String, Object> subQuestionTraceItem(SubQuestionEvidence item) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("index", item.getSubQuestionIndex());
        trace.put("question", StrUtil.blankToDefault(item.getSubQuestion(), ""));
        trace.put("referenceCount", item.getReferences() == null ? 0 : item.getReferences().size());
        trace.put("sourceDocumentCount", item.getSourceDocuments() == null ? 0 : item.getSourceDocuments().size());
        trace.put("contextDocumentCount", item.getContextDocuments() == null ? 0 : item.getContextDocuments().size());
        trace.put("fusedCandidateCount", item.getFusedCandidateCount() == null ? 0 : item.getFusedCandidateCount());
        trace.put("parentCandidateCount", item.getParentCandidateCount() == null ? 0 : item.getParentCandidateCount());
        trace.put("rerankedCandidateCount", item.getRerankedCandidateCount() == null ? 0 : item.getRerankedCandidateCount());
        trace.put("observationPersistence", requireObservationPersistence(item));
        trace.put("channelTraces", item.getChannelTraces() == null
            ? List.of()
            : item.getChannelTraces().stream().map(channelTrace -> Map.of(
                "channelName", StrUtil.blankToDefault(channelTrace.getChannelName(), ""),
                "retrievalIntent", StrUtil.blankToDefault(channelTrace.getRetrievalIntent(), ""),
                "channelWeight", channelTrace.getChannelWeight() == null ? 0D : channelTrace.getChannelWeight(),
                "recalledCount", channelTrace.getRecalledCount(),
                "acceptedCount", channelTrace.getAcceptedCount()
            )).toList());
        trace.put("evidenceCandidates", evidenceQualityTraceItems(item.getSourceDocuments(), item.getContextDocuments()));
        trace.put("references", referenceTraceItems(item.getReferences()));
        return trace;
    }

    private List<Map<String, Object>> evidenceQualityTraceItems(List<RetrievalDocument> sourceDocuments,
                                                                List<RetrievalDocument> contextDocuments) {
        List<Map<String, Object>> items = new ArrayList<>();
        appendEvidenceQualityTraceItems(items, sourceDocuments, "SOURCE");
        appendEvidenceQualityTraceItems(items, contextDocuments, "CONTEXT");
        return List.copyOf(items);
    }

    private void appendEvidenceQualityTraceItems(List<Map<String, Object>> target,
                                                 List<RetrievalDocument> documents,
                                                 String candidatePool) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        documents.stream()
            .filter(document -> document != null)
            .map(document -> evidenceQualityTraceItem(document, candidatePool))
            .forEach(target::add);
    }

    private Map<String, Object> evidenceQualityTraceItem(RetrievalDocument document, String candidatePool) {
        Map<String, Object> metadata = document.getMetadata() == null ? Map.of() : document.getMetadata();
        EvidenceQualityFeatures quality = EvidenceQualityFeatures.resolve(document);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("candidateId", EvidenceCandidateNormalizer.resolveCandidateId(document));
        item.put("candidatePool", candidatePool);
        item.put("evidenceLane", quality.citationCapable() ? "SOURCE" : "CONTEXT_ONLY");
        item.put("sourceType", metadataText(metadata, DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        item.put("channel", metadataText(metadata, DocumentKnowledgeMetadataKeys.CHANNEL));
        item.put("documentId", metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID));
        item.put("chunkId", metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID));
        item.put("parentBlockId", metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
        item.put("sectionPath", metadataText(metadata, DocumentKnowledgeMetadataKeys.SECTION_PATH));
        item.put("citationIdentity", EvidenceIdentityResolver.citationIdentityValue(document));
        item.put("contextIdentity", EvidenceIdentityResolver.contextIdentityValue(document));
        item.put(
            DocumentKnowledgeMetadataKeys.CONTEXT_ARTIFACT,
            metadataText(metadata, DocumentKnowledgeMetadataKeys.CONTEXT_ARTIFACT)
        );
        item.put(
            DocumentKnowledgeMetadataKeys.CONTEXT_DISPOSITION,
            metadataText(metadata, DocumentKnowledgeMetadataKeys.CONTEXT_DISPOSITION)
        );
        item.put(DocumentKnowledgeMetadataKeys.EVIDENCE_SOURCE_CAPABILITY, quality.sourceCapability());
        item.put(DocumentKnowledgeMetadataKeys.EVIDENCE_GROUNDING_CONFIDENCE, quality.groundingConfidence());
        item.put(DocumentKnowledgeMetadataKeys.EVIDENCE_QUOTE_COVERAGE, quality.quoteCoverage());
        item.put(DocumentKnowledgeMetadataKeys.EVIDENCE_SOURCE_KIND, quality.sourceKind());
        item.put(DocumentKnowledgeMetadataKeys.EVIDENCE_QUALITY_SIGNALS, String.join(",", quality.qualitySignals()));
        item.put(DocumentKnowledgeMetadataKeys.EVIDENCE_QUALITY_SIGNAL_SOURCE, String.join(",", quality.qualitySignalSources()));
        item.put("textPreview", traceTextPreview(document.getText()));
        return item;
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String traceTextPreview(String text) {
        String normalized = StrUtil.blankToDefault(text, "").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private List<Map<String, Object>> referenceTraceItems(List<SearchReference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        return references.stream()
            .filter(reference -> reference != null)
            .map(this::referenceTraceItem)
            .toList();
    }

    private Map<String, Object> referenceTraceItem(SearchReference reference) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""));
        item.put("sourceType", StrUtil.blankToDefault(reference.getSourceType(), ""));
        item.put("documentId", reference.getDocumentId());
        item.put("taskId", reference.getTaskId());
        item.put("documentName", StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()));
        item.put("chunkId", reference.getChunkId());
        item.put("chunkNo", reference.getChunkNo());
        item.put("parentBlockId", reference.getParentBlockId());
        item.put("parentBlockNo", reference.getParentBlockNo());
        item.put("sectionPath", StrUtil.blankToDefault(reference.getSectionPath(), ""));
        item.put("channel", StrUtil.blankToDefault(reference.getChannel(), ""));
        item.put("score", reference.getScore());
        item.put("pageNo", reference.getPageNo());
        item.put("pageRange", StrUtil.blankToDefault(reference.getPageRange(), ""));
        item.put("bboxJson", StrUtil.blankToDefault(reference.getBboxJson(), ""));
        item.put("finalSelectionReason", StrUtil.blankToDefault(reference.getFinalSelectionReason(), ""));
        item.put("evidenceApplicabilityStatus", StrUtil.blankToDefault(reference.getEvidenceApplicabilityStatus(), ""));
        item.put("evidenceApplicabilityReason", StrUtil.blankToDefault(reference.getEvidenceApplicabilityReason(), ""));
        item.put("tableId", reference.getTableId());
        item.put("tableNo", reference.getTableNo());
        item.put("tableTitle", StrUtil.blankToDefault(reference.getTableTitle(), ""));
        item.put("tableOperation", StrUtil.blankToDefault(reference.getTableOperation(), ""));
        item.put("tableMetricColumn", StrUtil.blankToDefault(reference.getTableMetricColumn(), ""));
        item.put("tableGroupByColumn", StrUtil.blankToDefault(reference.getTableGroupByColumn(), ""));
        item.put("tableMatchedRowCount", reference.getTableMatchedRowCount());
        item.put("tableEvidenceRowIds", reference.getTableEvidenceRowIds() == null ? List.of() : reference.getTableEvidenceRowIds());
        item.put("tableEvidenceRowNos", reference.getTableEvidenceRowNos() == null ? List.of() : reference.getTableEvidenceRowNos());
        item.put("tableEvidenceColumnIds", reference.getTableEvidenceColumnIds() == null ? List.of() : reference.getTableEvidenceColumnIds());
        item.put("tableEvidenceColumnNames", reference.getTableEvidenceColumnNames() == null ? List.of() : reference.getTableEvidenceColumnNames());
        item.put("tableEvidenceCellIds", reference.getTableEvidenceCellIds() == null ? List.of() : reference.getTableEvidenceCellIds());
        item.put("tableEvidenceCellCoordinates", reference.getTableEvidenceCellCoordinates() == null ? List.of() : reference.getTableEvidenceCellCoordinates());
        item.put("tableEvidenceCellBboxJsons", reference.getTableEvidenceCellBboxJsons() == null ? List.of() : reference.getTableEvidenceCellBboxJsons());
        item.put("kgEntityId", reference.getKgEntityId());
        item.put("kgEntityName", StrUtil.blankToDefault(reference.getKgEntityName(), ""));
        item.put("kgCanonicalEntityKey", StrUtil.blankToDefault(reference.getKgCanonicalEntityKey(), ""));
        item.put("kgCanonicalEntityName", StrUtil.blankToDefault(reference.getKgCanonicalEntityName(), ""));
        item.put("kgCanonicalEntityCount", reference.getKgCanonicalEntityCount());
        item.put("kgCanonicalDocumentCount", reference.getKgCanonicalDocumentCount());
        item.put("kgRelatedEntityId", reference.getKgRelatedEntityId());
        item.put("kgRelatedEntityName", StrUtil.blankToDefault(reference.getKgRelatedEntityName(), ""));
        item.put("kgRelationId", reference.getKgRelationId());
        item.put("kgRelationType", StrUtil.blankToDefault(reference.getKgRelationType(), ""));
        item.put("kgRelationGroupKey", StrUtil.blankToDefault(reference.getKgRelationGroupKey(), ""));
        item.put("kgRelationGroupRelationCount", reference.getKgRelationGroupRelationCount());
        item.put("kgRelationGroupEvidenceCount", reference.getKgRelationGroupEvidenceCount());
        item.put("kgRelationGroupDocumentCount", reference.getKgRelationGroupDocumentCount());
        item.put("kgCrossDocumentCommunityKey", StrUtil.blankToDefault(reference.getKgCrossDocumentCommunityKey(), ""));
        item.put("kgCrossDocumentCommunityEntityCount", reference.getKgCrossDocumentCommunityEntityCount());
        item.put("kgCrossDocumentCommunityRelationGroupCount", reference.getKgCrossDocumentCommunityRelationGroupCount());
        item.put("kgCrossDocumentCommunityEvidenceCount", reference.getKgCrossDocumentCommunityEvidenceCount());
        item.put("kgCrossDocumentCommunityDocumentCount", reference.getKgCrossDocumentCommunityDocumentCount());
        item.put("kgCommunityRankScore", reference.getKgCommunityRankScore());
        item.put("kgCommunityRankReasons", StrUtil.blankToDefault(reference.getKgCommunityRankReasons(), ""));
        item.put("kgEvidenceId", reference.getKgEvidenceId());
        item.put("kgGraphPath", StrUtil.blankToDefault(reference.getKgGraphPath(), ""));
        item.put("kgHopCount", reference.getKgHopCount());
        item.put("kgQueryPlanSource", StrUtil.blankToDefault(reference.getKgQueryPlanSource(), ""));
        item.put("kgQueryPlanAnswerTypes", StrUtil.blankToDefault(reference.getKgQueryPlanAnswerTypes(), ""));
        item.put("kgQueryPlanEntities", StrUtil.blankToDefault(reference.getKgQueryPlanEntities(), ""));
        item.put("kgNhopSeedEntityId", reference.getKgNhopSeedEntityId());
        item.put("kgNhopSeedEntityName", StrUtil.blankToDefault(reference.getKgNhopSeedEntityName(), ""));
        item.put("kgNhopPath", StrUtil.blankToDefault(reference.getKgNhopPath(), ""));
        item.put("kgQualityScore", reference.getKgQualityScore());
        item.put("kgQualityReasons", StrUtil.blankToDefault(reference.getKgQualityReasons(), ""));
        item.put("kgNoiseReasons", StrUtil.blankToDefault(reference.getKgNoiseReasons(), ""));
        item.put("kgPagerank", reference.getKgPagerank());
        item.put("kgRankPosition", reference.getKgRankPosition());
        item.put("kgDegree", reference.getKgDegree());
        return item;
    }

    private Flux<String> streamFromRetrievalContext(TaskInfo taskInfo,
                                                    ConversationExecutionPlan plan,
                                                    RagRetrievalContext context) {

        context.getRetrievalNotes().forEach(note -> ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, note));

        taskInfo.usedTools().addAll(context.getUsedChannels());
        taskInfo.debugTrace().setRetrievalNotes(new ArrayList<>(context.getRetrievalNotes()));
        taskInfo.debugTrace().setUsedChannels(new ArrayList<>(context.getUsedChannels()));

        ConversationTraceRecorder.StageHandle budgetStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.EVIDENCE_BUDGET, mode().name(), "正在组装证据与 Prompt 预算。", null);

        if (context.isEmpty()) {
            if (taskInfo.traceRecorder() != null) {
                taskInfo.traceRecorder().completeStage(
                    budgetStage,
                    "无 Source 证据，已完成空证据预算账本。",
                    evidenceBudgetSnapshot(context, null)
                );
            }

            ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前没有足够证据，直接返回无证据兜底回复。");
            return Flux.just(StrUtil.blankToDefault(plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。"));
        }

        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "证据整理完成，正在基于证据生成回答。");

        RagPromptAssemblyResult promptAssemblyResult = ragPromptAssemblyService.assemble(plan, context);
        taskInfo.setPromptAssemblyResult(promptAssemblyResult);
        String systemPrompt = promptAssemblyResult.getSystemPrompt();
        String userPrompt = promptAssemblyResult.getUserPrompt();
        taskInfo.debugTrace().setRagSystemPrompt(systemPrompt);
        taskInfo.debugTrace().setRagUserPrompt(userPrompt);
        if (taskInfo.traceRecorder() != null) {
            taskInfo.traceRecorder().completeStage(
                budgetStage,
                "证据预算与 Prompt 组装完成。",
                evidenceBudgetSnapshot(context, promptAssemblyResult)
            );
        }

        ConversationTraceRecorder.StageHandle answerStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.ANSWER_GENERATE, mode().name(), "正在基于证据生成回答。", null);
        var answerEnded = new java.util.concurrent.atomic.AtomicBoolean();
        return observedChatModelService.streamText("rag_answer", systemPrompt, userPrompt, taskInfo.traceRecorder())
            .doOnComplete(() -> {
                if (answerEnded.compareAndSet(false, true) && taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().completeStage(answerStage, "答案生成完成。", Map.of(
                        "firstResponseTimeMs", taskInfo.firstResponseTimeMs().get(),
                        "answerLength", taskInfo.answerBuffer().length()
                    ));
                }
            })
            .doOnError(error -> {
                if (answerEnded.compareAndSet(false, true) && taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(answerStage, "答案生成失败。", error.getMessage(), null);
                }
            })
            .doOnCancel(() -> {
                if (answerEnded.compareAndSet(false, true) && taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(answerStage, "答案生成已停止。", "CANCELLED", null);
                }
            });
    }

    private Map<String, Object> evidenceBudgetSnapshot(RagRetrievalContext context,
                                                       RagPromptAssemblyResult promptAssemblyResult) {
        List<SubQuestionEvidence> evidence = context == null || context.getSubQuestionEvidenceList() == null
            ? List.of()
            : context.getSubQuestionEvidenceList().stream()
                .filter(item -> item != null)
                .sorted(java.util.Comparator.comparingInt(SubQuestionEvidence::getSubQuestionIndex))
                .toList();
        validateEvidenceBudgetScope(context, evidence);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", "S09");
        snapshot.put("subQuestionCount", evidence.size());
        snapshot.put("subQuestions", evidence.stream().map(this::evidenceBudgetSubQuestion).toList());
        snapshot.put("totalBudget", promptAssemblyResult == null ? 0 : promptAssemblyResult.getTotalBudget());
        snapshot.put("perSubQuestionBudget", promptAssemblyResult == null ? 0 : promptAssemblyResult.getPerSubQuestionBudget());
        snapshot.put("inputReferenceCount", promptAssemblyResult == null ? 0 : promptAssemblyResult.getInputReferenceCount());
        snapshot.put("referenceManifest", promptAssemblyResult == null ? List.of() : promptAssemblyResult.getReferenceManifest());
        snapshot.put("renderedSourceEvidence", promptAssemblyResult == null
            ? List.of()
            : promptAssemblyResult.getRenderedSourceEvidence().stream()
                .map(this::renderedSourceEvidenceSnapshot)
                .toList());
        snapshot.put("renderedSourceIdentities", promptAssemblyResult == null ? List.of() : promptAssemblyResult.getRenderedSourceIdentities());
        snapshot.put("renderedContextIdentities", promptAssemblyResult == null ? List.of() : promptAssemblyResult.getRenderedContextIdentities());
        snapshot.put("explicitCitationEligibleIdentities", promptAssemblyResult == null ? List.of() : promptAssemblyResult.getRenderedSourceIdentities());
        snapshot.put("answerShapeRequirements", promptAssemblyResult == null
            ? List.of()
            : promptAssemblyResult.getAnswerShapeRequirements().stream().map(Enum::name).toList());
        snapshot.put("PROMPT_RENDERED_CONTEXT", promptAssemblyResult == null ? List.of() : promptAssemblyResult.getRenderedContextIdentities());
        snapshot.put("systemPrompt", promptAssemblyResult == null ? "" : StrUtil.blankToDefault(promptAssemblyResult.getSystemPrompt(), ""));
        snapshot.put("userPrompt", promptAssemblyResult == null ? "" : StrUtil.blankToDefault(promptAssemblyResult.getUserPrompt(), ""));
        return snapshot;
    }

    private Map<String, Object> renderedSourceEvidenceSnapshot(PromptRenderedSourceEvidence evidence) {
        SearchReference reference = evidence.reference();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("identity", evidence.identity());
        item.put("referenceId", reference == null ? "" : StrUtil.blankToDefault(reference.getReferenceId(), ""));
        item.put("sourceType", reference == null ? "" : StrUtil.blankToDefault(reference.getSourceType(), ""));
        item.put("evidenceType", reference == null ? "" : StrUtil.blankToDefault(reference.getCitationEvidenceType(), ""));
        item.put("documentId", reference == null ? null : reference.getDocumentId());
        item.put("documentContentVersion", reference == null || reference.getTaskId() == null
            ? ""
            : "parse-task:" + reference.getTaskId());
        item.put("text", evidence.renderedText());
        return item;
    }

    private Map<String, Object> evidenceBudgetSubQuestion(SubQuestionEvidence evidence) {
        if (evidence.getEvidenceSelectionLedger() == null) {
            throw new IllegalStateException("Every sub-question requires a complete evidence selection ledger");
        }
        var ledger = evidence.getEvidenceSelectionLedger();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("subQuestionIndex", evidence.getSubQuestionIndex());
        block.put("executionQuery", StrUtil.blankToDefault(evidence.getSubQuestion(), ""));
        block.put("observationPersistence", requireObservationPersistence(evidence));
        block.put("evidenceLineage", ledger.evidenceLineage());
        block.put("rerankExecution", ledger.rerankExecution());
        block.put("sourceRankingWindowDecisions", ledger.sourceRankingWindowDecisions());
        block.put("sourceRankingWindowCandidates", ledger.sourceRankingWindowCandidates());
        block.put("rerankRequestCandidates", ledger.rerankRequestCandidates());
        block.put("rerankResultCandidates", ledger.rerankResultCandidates());
        block.put("identityTransitions", ledger.identityTransitions());
        block.put("finalEvidenceDecisions", ledger.finalEvidenceDecisions());
        return block;
    }

    private ObservationPersistence requireObservationPersistence(SubQuestionEvidence evidence) {
        if (evidence.getObservationPersistence() == null) {
            throw new IllegalStateException("Every sub-question requires an observation persistence outcome");
        }
        return evidence.getObservationPersistence();
    }

    private void validateEvidenceBudgetScope(RagRetrievalContext context, List<SubQuestionEvidence> evidence) {
        List<Integer> evidenceIndexes = evidence.stream().map(SubQuestionEvidence::getSubQuestionIndex).toList();
        if (evidenceIndexes.stream().anyMatch(index -> index == null || index <= 0)
            || evidenceIndexes.stream().distinct().count() != evidenceIndexes.size()) {
            throw new IllegalStateException("EVIDENCE_BUDGET subQuestionIndex values must be positive and unique");
        }
        List<Integer> requestIndexes = context == null || context.getExecutionRequests() == null
            ? List.of()
            : context.getExecutionRequests().stream()
                .map(RetrievalExecutionRequest::subQuestionIndex)
                .sorted()
                .toList();
        if (!requestIndexes.equals(evidenceIndexes)) {
            throw new IllegalStateException("EVIDENCE_BUDGET blocks must match execution request indexes");
        }
    }
}
