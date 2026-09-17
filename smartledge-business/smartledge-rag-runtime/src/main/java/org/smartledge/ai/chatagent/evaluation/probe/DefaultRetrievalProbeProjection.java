package org.smartledge.ai.chatagent.evaluation.probe;

import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.ExecutionMode;
import org.smartledge.ai.chatagent.rag.model.RagRetrievalContext;
import org.smartledge.ai.rag.runtime.model.RagRuntimeOptions;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.model.RouteScopeAuthorizationMode;
import org.smartledge.ai.chatagent.rag.model.SubQuestionEvidence;
import org.smartledge.ai.chatagent.rag.service.KnowledgeRoutePlan;
import org.smartledge.ai.chatagent.rag.service.RagRetrievalEngine;
import org.smartledge.ai.chatagent.rag.service.RetrievalPlanAssembler;
import org.smartledge.ai.chatagent.rag.support.EvidenceIdentityResolver;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.rag.runtime.port.KnowledgeScopePort;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.smartledge.enums.RetrievalChannelEnum;
import org.smartledge.exception.SuperAgentFrameException;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class DefaultRetrievalProbeProjection implements RetrievalProbeProjection {

    private static final Map<String, String> CHANNEL_ALIASES = channelAliases();

    private final KnowledgeScopePort scopeService;
    private final RetrievalPlanAssembler planAssembler;
    private final RagRetrievalEngine retrievalEngine;
    private final RetrievalProbeProperties properties;

    public DefaultRetrievalProbeProjection(KnowledgeScopePort scopeService,
                                           RetrievalPlanAssembler planAssembler,
                                           RagRetrievalEngine retrievalEngine,
                                           RetrievalProbeProperties properties) {
        this.scopeService = scopeService;
        this.planAssembler = planAssembler;
        this.retrievalEngine = retrievalEngine;
        this.properties = properties;
    }

    @Override
    public RetrievalProbeResult probe(RetrievalProbeQuery query) {
        validate(query);
        validateChannelOverride(query.getOverrides());
        boolean documentMode = query.getDocumentId() != null;
        ChatQueryMode chatMode = documentMode ? ChatQueryMode.DOCUMENT : ChatQueryMode.AUTO_DOCUMENT;
        KnowledgeBaseSelectionMode selectionMode = query.getSelectionMode() == null
            ? KnowledgeBaseSelectionMode.SELECTED : query.getSelectionMode();
        KnowledgeBaseSelectionSnapshot scope;
        try {
            scope = scopeService.resolve(chatMode, selectionMode, query.getKnowledgeBaseIds());
        } catch (SuperAgentFrameException exception) {
            throw new RetrievalProbeException("INVALID_SCOPE", "knowledge scope could not be resolved");
        }
        if (scope == null) {
            throw new RetrievalProbeException("INVALID_SCOPE", "knowledge scope could not be resolved");
        }
        List<Long> documentIds = new ArrayList<>();
        List<Long> taskIds = new ArrayList<>();
        List<KnowledgeDocumentDescriptor> allowedDocuments = scope.getAllowedDocuments();
        if (allowedDocuments == null) {
            throw new RetrievalProbeException("INVALID_SCOPE", "resolved retrieval scope has no document facts");
        }
        if (documentMode) {
            KnowledgeDocumentDescriptor descriptor = allowedDocuments.stream()
                .filter(Objects::nonNull)
                .filter(item -> query.getDocumentId().equals(item.getDocumentId()))
                .findFirst()
                .orElseThrow(() -> new RetrievalProbeException("INVALID_SCOPE", "documentId is outside the resolved knowledge scope"));
            if (descriptor.getDocumentId() == null || descriptor.getDocumentId() <= 0L
                || descriptor.getLastIndexTaskId() == null || descriptor.getLastIndexTaskId() <= 0L) {
                throw new RetrievalProbeException("INVALID_SCOPE", "document scope has no retrievable index task");
            }
            documentIds.add(descriptor.getDocumentId());
            taskIds.add(descriptor.getLastIndexTaskId());
        } else {
            for (KnowledgeDocumentDescriptor item : allowedDocuments) {
                if (item == null || item.getDocumentId() == null || item.getDocumentId() <= 0L
                    || item.getLastIndexTaskId() == null || item.getLastIndexTaskId() <= 0L) {
                    throw new RetrievalProbeException("INVALID_SCOPE",
                        "resolved retrieval scope contains an invalid document/index task pair");
                }
                documentIds.add(item.getDocumentId());
                taskIds.add(item.getLastIndexTaskId());
            }
        }
        if (documentIds.isEmpty() || documentIds.size() != taskIds.size()) {
            throw new RetrievalProbeException("INVALID_SCOPE", "resolved retrieval scope is empty or inconsistent");
        }
        RagRuntimeOptions runtime = scope.getRagRuntimeOptions() == null
            ? RagRuntimeOptions.defaults() : scope.getRagRuntimeOptions().deepCopy();
        applyOverrides(runtime, query.getOverrides());
        KnowledgeRoutePlan route = KnowledgeRoutePlan.builder()
            .authorizationMode(documentMode
                ? RouteScopeAuthorizationMode.EXPLICIT_DOCUMENT
                : RouteScopeAuthorizationMode.KNOWLEDGE_BASE_ALLOWED_SCOPE)
            .scopeAuthorizationReason("Retrieval probe uses the Java-resolved retrieval scope")
            .recommendedDocumentId(documentMode ? documentIds.get(0) : null)
            .recommendedTaskId(documentMode ? taskIds.get(0) : null)
            .authorizedDocumentIds(documentIds)
            .authorizedTaskIds(taskIds)
            .build();
        RetrievalPlan plan = planAssembler.assemble(RetrievalPlanAssembler.AssemblyInput.builder()
            .chatMode(chatMode)
            .originalQuestion(query.getQuery())
            .knowledgeBaseSelectionMode(selectionMode)
            .knowledgeBaseIds(scope.getSelectedKnowledgeBaseIds())
            .allowedDocumentIds(scope.getAllowedDocumentIds())
            .documentScope(documentIds)
            .taskScope(taskIds)
            .knowledgeRoutePlan(route)
            .runtimeOptions(runtime)
            .build());
        ConversationExecutionPlan executionPlan = ConversationExecutionPlan.builder()
            .mode(ExecutionMode.RETRIEVAL)
            .chatMode(chatMode)
            .originalQuestion(query.getQuery())
            .retrievalPlan(plan)
            .build();
        RagRetrievalContext context = retrievalEngine.retrieve(executionPlan, null);
        return project(query, plan, context, scope, runtime);
    }

    private void validate(RetrievalProbeQuery query) {
        if (query == null || !RetrievalProbeQuery.SCHEMA_VERSION.equals(query.getSchemaVersion())) {
            throw new RetrievalProbeException("INVALID_REQUEST", "unsupported retrieval probe schema");
        }
        if (query.getUnknownProperties() != null && !query.getUnknownProperties().isEmpty()) {
            if (query.getUnknownProperties().keySet().stream().anyMatch(this::isBuildTimeParameter)) {
                throw new RetrievalProbeException("INVALID_OVERRIDE", "解析、切块、向量化和 GraphRAG/RAPTOR 构建参数需要新的索引批次");
            }
            throw new RetrievalProbeException("INVALID_REQUEST", "unknown retrieval probe request field");
        }
        if (query.getExperimentId() == null || !query.getExperimentId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")) {
            throw new RetrievalProbeException("INVALID_EXPERIMENT_ID", "experimentId must be a stable traceable identifier");
        }
        if (query.getQuery() == null || query.getQuery().isBlank() || query.getQuery().length() > properties.getMaxQueryLength()) {
            throw new RetrievalProbeException("INVALID_QUERY", "query is blank or exceeds the configured limit");
        }
        if (query.getSelectionMode() == null || query.getSelectionMode() == KnowledgeBaseSelectionMode.NONE) {
            throw new RetrievalProbeException("INVALID_SCOPE", "retrieval probe requires an explicit knowledge scope");
        }
        if (query.getSelectionMode() == KnowledgeBaseSelectionMode.SELECTED
            && (query.getKnowledgeBaseIds() == null || query.getKnowledgeBaseIds().isEmpty())) {
            throw new RetrievalProbeException("INVALID_SCOPE", "knowledgeBaseIds are required");
        }
    }

    private void applyOverrides(RagRuntimeOptions runtime, RetrievalProbeOverrides overrides) {
        if (overrides == null) {
            return;
        }
        if (overrides.getBuildParameters() != null && !overrides.getBuildParameters().isEmpty()) {
            throw new RetrievalProbeException("INVALID_OVERRIDE", "解析、切块、向量化和 GraphRAG/RAPTOR 构建参数需要新的索引批次");
        }
        if (overrides.getCandidateTopK() != null) runtime.setCandidateTopK(overrides.getCandidateTopK());
        if (overrides.getRerankCandidateTopK() != null) runtime.setRerankCandidateTopK(overrides.getRerankCandidateTopK());
        if (overrides.getFinalTopK() != null) runtime.setFinalTopK(overrides.getFinalTopK());
        if (overrides.getRerankEnabled() != null) runtime.setRerankEnabled(overrides.getRerankEnabled());
        if (overrides.getEnabledChannels() != null && !overrides.getEnabledChannels().isEmpty()) {
            Set<String> channels = canonicalChannels(overrides.getEnabledChannels());
            runtime.setKeywordChannelEnabled(channels.contains(RetrievalChannelEnum.KEYWORD.getName()));
            runtime.setTableChannelEnabled(channels.contains(RetrievalChannelEnum.TABLE.getName()));
            runtime.setGraphRagChannelEnabled(channels.contains(RetrievalChannelEnum.GRAPH_RAG.getName()));
            runtime.setRaptorChannelEnabled(channels.contains(RetrievalChannelEnum.RAPTOR.getName()));
            if (!channels.contains(RetrievalChannelEnum.VECTOR.getName())) {
                throw new RetrievalProbeException("INVALID_OVERRIDE", "VECTOR channel cannot be disabled");
            }
        }
        if (runtime.getCandidateTopK() <= 0 || runtime.getRerankCandidateTopK() <= 0
            || runtime.getFinalTopK() <= 0 || runtime.getRerankCandidateTopK() > runtime.getCandidateTopK()
            || runtime.getFinalTopK() > runtime.getRerankCandidateTopK()) {
            throw new RetrievalProbeException("INVALID_OVERRIDE", "query-time ranking budgets are inconsistent");
        }
    }

    private void validateChannelOverride(RetrievalProbeOverrides overrides) {
        if (overrides == null) {
            return;
        }
        if (overrides.getBuildParameters() != null && !overrides.getBuildParameters().isEmpty()) {
            throw new RetrievalProbeException("INVALID_OVERRIDE", "解析、切块、向量化和 GraphRAG/RAPTOR 构建参数需要新的索引批次");
        }
        if (overrides.getUnknownProperties() != null && !overrides.getUnknownProperties().isEmpty()) {
            if (overrides.getUnknownProperties().keySet().stream().anyMatch(this::isBuildTimeParameter)) {
                throw new RetrievalProbeException("INVALID_OVERRIDE", "解析、切块、向量化和 GraphRAG/RAPTOR 构建参数需要新的索引批次");
            }
            throw new RetrievalProbeException("INVALID_OVERRIDE", "unknown retrieval probe override field");
        }
        int maximum = Math.max(1, properties.getMaxResultCount());
        if (Stream.of(
                overrides.getCandidateTopK(),
                overrides.getRerankCandidateTopK(),
                overrides.getFinalTopK()
            ).filter(Objects::nonNull)
            .anyMatch(value -> value <= 0 || value > maximum)) {
            throw new RetrievalProbeException("INVALID_OVERRIDE",
                "query-time ranking budgets must be positive and within the probe result limit");
        }
        if (overrides.getEnabledChannels() == null || overrides.getEnabledChannels().isEmpty()) {
            return;
        }
        Set<String> channels = canonicalChannels(overrides.getEnabledChannels());
        if (!channels.contains(RetrievalChannelEnum.VECTOR.getName())) {
            throw new RetrievalProbeException("INVALID_OVERRIDE", "VECTOR channel cannot be disabled");
        }
    }

    private Set<String> canonicalChannels(List<String> channels) {
        Set<String> canonical = new HashSet<>();
        for (String channel : channels) {
            String resolved = channel == null ? null : CHANNEL_ALIASES.get(channel);
            if (resolved == null || !canonical.add(resolved)) {
                throw new RetrievalProbeException("INVALID_OVERRIDE",
                    resolved == null
                        ? "enabledChannels contains an unknown retrieval channel"
                        : "enabledChannels contains a duplicate retrieval channel");
            }
        }
        return canonical;
    }

    private static Map<String, String> channelAliases() {
        Map<String, String> aliases = new HashMap<>();
        List.of(
            RetrievalChannelEnum.VECTOR,
            RetrievalChannelEnum.KEYWORD,
            RetrievalChannelEnum.TABLE,
            RetrievalChannelEnum.GRAPH_RAG,
            RetrievalChannelEnum.RAPTOR
        ).forEach(channel -> {
            aliases.put(channel.getName(), channel.getName());
            aliases.put(channel.name(), channel.getName());
        });
        return Map.copyOf(aliases);
    }

    private boolean isBuildTimeParameter(String name) {
        String normalized = name == null ? "" : name.replaceAll("[-_]", "").toLowerCase(Locale.ROOT);
        return normalized.contains("parser") || normalized.contains("chunk") || normalized.contains("embedding")
            || normalized.contains("graphrag") || normalized.contains("raptor");
    }

    private RetrievalProbeResult project(RetrievalProbeQuery query,
                                         RetrievalPlan plan,
                                         RagRetrievalContext context,
                                         KnowledgeBaseSelectionSnapshot scope,
                                         RagRuntimeOptions runtime) {
        List<RetrievalProbeSubQuestion> subQuestions = context.getSubQuestionEvidenceList() == null ? List.of()
            : context.getSubQuestionEvidenceList().stream().map(this::projectSubQuestion).toList();
        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("candidateTopK", runtime.getCandidateTopK());
        effective.put("rerankCandidateTopK", runtime.getRerankCandidateTopK());
        effective.put("finalTopK", runtime.getFinalTopK());
        effective.put("rerankEnabled", runtime.isRerankEnabled());
        effective.put("enabledChannels", plan.enabledChannelNames());
        effective.put("vectorTopK", runtime.getVectorTopK());
        effective.put("keywordTopK", runtime.getKeywordTopK());
        effective.put("graphRagTopK", runtime.getGraphRagTopK());
        effective.put("graphRagMaxHops", runtime.getGraphRagMaxHops());
        effective.put("raptorTopK", runtime.getRaptorTopK());
        effective.put("raptorSourceChunkTopK", runtime.getRaptorSourceChunkTopK());
        effective.put("channelTimeoutMs", runtime.getChannelTimeoutMs());
        effective.put("subQuestionTimeoutMs", runtime.getSubQuestionTimeoutMs());
        effective.put("minVectorSimilarity", runtime.getMinVectorSimilarity());
        effective.put("keywordRelativeScoreFloor", runtime.getKeywordRelativeScoreFloor());
        effective.put("hybrid", hybridConfiguration(runtime));
        List<Map<String, Object>> documents = scope.getAllowedDocuments().stream().map(item -> {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("documentId", item.getDocumentId());
            descriptor.put("indexTaskId", item.getLastIndexTaskId());
            descriptor.put("knowledgeBaseId", item.getKnowledgeBaseId());
            descriptor.put("documentName", item.getDocumentName());
            descriptor.put("knowledgeBaseName", item.getKnowledgeBaseName());
            return descriptor;
        }).toList();
        return RetrievalProbeResult.builder()
            .schemaVersion(RetrievalProbeResult.SCHEMA_VERSION)
            .experimentId(query.getExperimentId())
            .retrievalPlan(plan)
            .retrievalQuestion(context.getRetrievalQuestion())
            .usedChannels(context.getUsedChannels() == null ? List.of() : List.copyOf(context.getUsedChannels()))
            .subQuestions(subQuestions)
            .effectiveConfiguration(effective)
            .corpusIndexProvenance(Map.of(
                "documents", documents,
                "scopeMode", plan.getScopeMode() == null ? "UNKNOWN" : plan.getScopeMode().name(),
                "knowledgeBaseIds", safeList(scope.getSelectedKnowledgeBaseIds()),
                "documentIds", safeList(plan.getDocumentScope()),
                "indexTaskIds", safeList(plan.getTaskScope()),
                "provenanceSource", "JAVA_KNOWLEDGE_SCOPE_AND_INDEX_TASKS"
            ))
            .build();
    }

    private Map<String, Object> hybridConfiguration(RagRuntimeOptions runtime) {
        RagRuntimeOptions.HybridOptions hybrid = runtime.getHybrid();
        if (hybrid == null) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("vectorWeight", hybrid.getVectorWeight());
        values.put("keywordWeight", hybrid.getKeywordWeight());
        values.put("tableWeight", hybrid.getTableWeight());
        values.put("graphRagWeight", hybrid.getGraphRagWeight());
        values.put("raptorWeight", hybrid.getRaptorWeight());
        values.put("rankWeight", hybrid.getRankWeight());
        values.put("originalScoreWeight", hybrid.getOriginalScoreWeight());
        values.put("metadataBoostWeight", hybrid.getMetadataBoostWeight());
        values.put("maxMetadataBoost", hybrid.getMaxMetadataBoost());
        return values;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private RetrievalProbeSubQuestion projectSubQuestion(SubQuestionEvidence item) {
        return RetrievalProbeSubQuestion.builder()
            .subQuestionIndex(item.getSubQuestionIndex())
            .subQuestion(item.getSubQuestion())
            .channelTraces(item.getChannelTraces() == null ? List.of() : List.copyOf(item.getChannelTraces()))
            .fusedCandidateCount(item.getFusedCandidateCount() == null ? 0 : item.getFusedCandidateCount())
            .parentCandidateCount(item.getParentCandidateCount() == null ? 0 : item.getParentCandidateCount())
            .rerankedCandidateCount(item.getRerankedCandidateCount() == null ? 0 : item.getRerankedCandidateCount())
            .sourceCandidateIdentities(sourceIdentities(item.getSourceDocuments()))
            .contextCandidateIdentities(contextIdentities(item.getContextDocuments()))
            .evidenceSelectionLedger(item.getEvidenceSelectionLedger())
            .build();
    }

    private List<String> sourceIdentities(List<RetrievalDocument> documents) {
        if (documents == null) return List.of();
        return documents.stream().map(EvidenceIdentityResolver::citationIdentityValue).filter(value -> !value.isBlank())
            .limit(Math.max(1, properties.getMaxResultCount())).toList();
    }

    private List<String> contextIdentities(List<RetrievalDocument> documents) {
        if (documents == null) return List.of();
        return documents.stream().map(EvidenceIdentityResolver::contextIdentityValue).filter(value -> !value.isBlank())
            .limit(Math.max(1, properties.getMaxResultCount())).toList();
    }
}
