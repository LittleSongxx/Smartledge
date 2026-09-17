package org.smartledge.ai.chatagent.evaluation;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.smartledge.ai.chatagent.model.ChannelExecutionView;
import org.smartledge.ai.chatagent.model.RetrievalResultView;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.rag.runtime.model.ChatModelUsageTrace;
import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageView;
import org.smartledge.enums.ChatTurnStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DefaultEvaluationExchangeSnapshotProjection implements EvaluationExchangeSnapshotProjection {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final EvaluationExchangeSnapshotFactSource factSource;
    private final ObjectMapper objectMapper;
    private final EvaluationSnapshotProperties properties;
    private final EvaluationSnapshotRedactor redactor;

    DefaultEvaluationExchangeSnapshotProjection(EvaluationExchangeSnapshotFactSource factSource,
                                                ObjectMapper objectMapper,
                                                EvaluationSnapshotProperties properties,
                                                EvaluationSnapshotRedactor redactor) {
        this.factSource = factSource;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.redactor = redactor;
    }

    @Override
    public List<EvaluationExchangeSnapshot> query(EvaluationExchangeSnapshotQuery query) {
        validateQuery(query);
        Map<Long, EvaluationExchangeSnapshotFacts> factsById = factSource.load(query.exchangeIds());
        List<EvaluationExchangeSnapshot> snapshots = new ArrayList<>(query.exchangeIds().size());
        for (Long exchangeId : query.exchangeIds()) {
            EvaluationExchangeSnapshotFacts facts = factsById.get(exchangeId);
            if (facts == null) {
                throw new EvaluationSnapshotQueryException(
                    "EXCHANGE_NOT_FOUND",
                    "Evaluation exchange was not found: " + exchangeId
                );
            }
            if (facts.status() == null || facts.status() == ChatTurnStatus.RUNNING) {
                throw new EvaluationSnapshotQueryException(
                    "EXCHANGE_NOT_TERMINAL",
                    "Evaluation exchange is not terminal: " + exchangeId
                );
            }
            snapshots.add(project(facts, query.includeDiagnostics(), query.schemaVersion()));
        }
        return List.copyOf(snapshots);
    }

    private void validateQuery(EvaluationExchangeSnapshotQuery query) {
        if (query == null) {
            throw new EvaluationSnapshotQueryException("QUERY_REQUIRED", "Evaluation snapshot query is required");
        }
        if (!EvaluationExchangeSnapshot.SCHEMA_VERSION.equals(query.schemaVersion())
            && !EvaluationExchangeSnapshot.SCHEMA_VERSION_V2.equals(query.schemaVersion())
            && !EvaluationExchangeSnapshot.SCHEMA_VERSION_V3.equals(query.schemaVersion())) {
            throw new EvaluationSnapshotQueryException(
                "UNKNOWN_SCHEMA_VERSION",
                "Unsupported Evaluation Exchange Snapshot schema: " + query.schemaVersion()
            );
        }
        if (query.exchangeIds().isEmpty()) {
            throw new EvaluationSnapshotQueryException("EXCHANGE_IDS_REQUIRED", "At least one exchange ID is required");
        }
        if (query.exchangeIds().size() > Math.max(1, properties.getMaxBatchSize())) {
            throw new EvaluationSnapshotQueryException("BATCH_LIMIT_EXCEEDED", "Evaluation snapshot batch limit exceeded");
        }
        Set<Long> unique = new HashSet<>();
        for (Long exchangeId : query.exchangeIds()) {
            if (exchangeId == null || exchangeId <= 0 || !unique.add(exchangeId)) {
                throw new EvaluationSnapshotQueryException(
                    "INVALID_EXCHANGE_ID_LIST",
                    "Exchange IDs must be positive and unique"
                );
            }
        }
    }

    private EvaluationExchangeSnapshot project(EvaluationExchangeSnapshotFacts facts,
                                               boolean includeDiagnostics,
                                               String schemaVersion) {
        Map<String, ConversationTraceStageView> stages = latestStageByCode(facts.stages());
        Map<String, Object> retrievalSnapshot = snapshot(stages.get("RAG_RETRIEVE"));
        Map<String, Object> planMap = map(retrievalSnapshot.get("retrievalPlan"));
        Map<String, Object> budgetSnapshot = snapshot(stages.get("EVIDENCE_BUDGET"));
        Map<String, Object> citationSnapshot = snapshot(stages.get("CITATION_BINDING"));
        Map<String, Object> finalizeSnapshot = snapshot(stages.get("FINALIZE"));

        EvaluationExchangeSnapshot.Scope scope = scope(facts, planMap);
        EvaluationExchangeSnapshot.Prompt prompt = prompt(budgetSnapshot, schemaVersion);
        EvaluationExchangeSnapshot.Citation citation = citation(budgetSnapshot, citationSnapshot);
        EvaluationExchangeSnapshot.Archive archive = archive(facts, citationSnapshot);
        List<String> observedSelected = facts.retrievalResults().stream()
            .filter(Objects::nonNull)
            .filter(RetrievalResultView::isSelected)
            .filter(result -> !result.isContextOnly())
            .map(RetrievalResultView::getCitationIdentity)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        List<String> finalizeIdentities = strings(finalizeSnapshot.get("sourceSnapshotIdentities"));
        List<String> citationSnapshotIdentities = strings(citationSnapshot.get("sourceSnapshotIdentities"));
        List<String> retrievedIdentities = retrievedIdentities(citationSnapshot, prompt.renderedSourceIdentities());
        List<String> finalizeRetrievedIdentities = strings(finalizeSnapshot.get("retrievedSourceIdentities"));
        EvaluationExchangeSnapshot.Conservation conservation = conservation(
            prompt.renderedSourceIdentities(),
            citation.boundIdentities(),
            citationSnapshotIdentities,
            archive.sourceSnapshotIdentities(),
            finalizeIdentities,
            observedSelected,
            retrievedIdentities,
            archive.retrievedSourceIdentities() == null ? List.of() : archive.retrievedSourceIdentities(),
            finalizeRetrievedIdentities,
            finalizeSnapshot.containsKey("retrievedSourceIdentities"),
            stages
        );
        EvaluationExchangeSnapshot.Provenance provenance = provenance(facts, scope, prompt);
        EvaluationExchangeSnapshot.HardScope hardScope =
            EvaluationExchangeSnapshot.SCHEMA_VERSION_V3.equals(schemaVersion)
                ? hardScope(scope, planMap, retrievalSnapshot, facts.channelExecutions())
                : null;
        EvaluationExchangeSnapshot.StorageFilters storageFilters =
            EvaluationExchangeSnapshot.SCHEMA_VERSION_V3.equals(schemaVersion)
                ? storageFilters(planMap, retrievalSnapshot, facts.channelExecutions())
                : null;
        EvaluationExchangeSnapshot.QueryHints queryHints =
            EvaluationExchangeSnapshot.SCHEMA_VERSION_V3.equals(schemaVersion)
                ? queryHints(planMap, retrievalSnapshot, facts.channelExecutions())
                : null;
        EvaluationExchangeSnapshot.EvidenceApplicability evidenceApplicability =
            EvaluationExchangeSnapshot.SCHEMA_VERSION_V3.equals(schemaVersion)
                ? evidenceApplicability(planMap, retrievalSnapshot)
                : null;

        return new EvaluationExchangeSnapshot(
            schemaVersion,
            new EvaluationExchangeSnapshot.Exchange(
                facts.exchangeId(),
                safe(facts.conversationId()),
                facts.status().name(),
                terminalOutcome(facts.status(), prompt, stages),
                facts.createdAt(),
                facts.terminalAt()
            ),
            new EvaluationExchangeSnapshot.Input(
                redactor.redactText(facts.question()),
                redactor.redactText(normalizedInput(facts, planMap))
            ),
            scope,
            retrieval(facts, retrievalSnapshot, planMap),
            evidence(facts),
            prompt,
            new EvaluationExchangeSnapshot.Answer(redactor.redactText(facts.answer())),
            citation,
            archive,
            conservation,
            provenance,
            readiness(facts, stages, prompt, citation, conservation, provenance, planMap, queryHints),
            diagnostics(includeDiagnostics, facts.stages()),
            hardScope,
            storageFilters,
            queryHints,
            evidenceApplicability
        );
    }

    private EvaluationExchangeSnapshot.Scope scope(EvaluationExchangeSnapshotFacts facts,
                                                    Map<String, Object> plan) {
        List<Long> knowledgeBaseIds = longs(plan.get("knowledgeBaseIds"));
        if (knowledgeBaseIds.isEmpty()) {
            knowledgeBaseIds = facts.selectedKnowledgeBaseIds().stream()
                .map(this::positiveLong)
                .filter(Objects::nonNull)
                .toList();
        }
        return new EvaluationExchangeSnapshot.Scope(
            text(plan.get("scopeMode"), facts.knowledgeBaseSelectionMode()),
            knowledgeBaseIds,
            longs(plan.get("allowedDocumentScope")),
            longs(plan.get("documentScope")),
            longs(plan.get("taskScope"))
        );
    }

    private EvaluationExchangeSnapshot.HardScope hardScope(EvaluationExchangeSnapshot.Scope scope,
                                                            Map<String, Object> plan,
                                                            Map<String, Object> retrievalSnapshot,
                                                            List<ChannelExecutionView> channelExecutions) {
        Map<String, Object> request = firstMap(retrievalSnapshot.get("executionRequests"));
        boolean consumed = channelExecutions != null && !channelExecutions.isEmpty();
        Object plannedKnowledgeBases = valueOrFallback(plan, "knowledgeBaseIds", scope.knowledgeBaseIds());
        Object plannedAllowedDocuments = valueOrNull(plan, "allowedDocumentScope");
        Object plannedExecutionDocuments = valueOrNull(plan, "documentScope");
        Object plannedTasks = valueOrNull(plan, "taskScope");
        Object plannedScopeMode = valueOrFallback(plan, "scopeMode", scope.mode());
        Object plannedRouteAuthorization = mapOrNull(plan.get("routePlan"));
        return new EvaluationExchangeSnapshot.HardScope(
            layered(plannedKnowledgeBases, valueOrNull(request, "knowledgeBaseIds"), consumed, "RetrievalPlan"),
            layered(plannedAllowedDocuments, valueOrNull(request, "allowedDocumentScope"), consumed, "RetrievalPlan"),
            layered(plannedExecutionDocuments, valueOrNull(request, "documentScope"), consumed, "RetrievalPlan"),
            layered(plannedTasks, valueOrNull(request, "taskScope"), consumed, "RetrievalPlan"),
            layered(plannedScopeMode, valueOrNull(request, "scopeMode"), consumed, "RetrievalPlan"),
            routeAuthorization(plannedRouteAuthorization, request)
        );
    }

    private EvaluationExchangeSnapshot.LayeredValue routeAuthorization(Object planned,
                                                                        Map<String, Object> request) {
        if (planned == null || request.isEmpty()) {
            return layered(planned, null, false, "RetrievalPlan");
        }
        return new EvaluationExchangeSnapshot.LayeredValue(
            planned,
            planned,
            "RETRIEVAL_PLAN_VALIDATION",
            "CONSUMED",
            "RetrievalPlan",
            ""
        );
    }

    private EvaluationExchangeSnapshot.LayeredValue layered(Object planned,
                                                             Object projected,
                                                             boolean consumed,
                                                             String source) {
        boolean hasProjected = projected != null;
        String status = hasProjected && consumed
            ? "CONSUMED"
            : planned != null || hasProjected ? "NOT_CONSUMED" : "NOT_AVAILABLE";
        String consumedStage = hasProjected && consumed ? "RAG_RETRIEVE" : "";
        String reason = hasProjected && consumed
            ? ""
            : hasProjected ? "MISSING_CHANNEL_EXECUTION_FACT"
            : planned != null ? "MISSING_EXECUTION_REQUEST"
            : "MISSING_RETRIEVAL_PLAN_FACT";
        return new EvaluationExchangeSnapshot.LayeredValue(
            planned,
            projected,
            consumedStage,
            status,
            source,
            reason
        );
    }

    private Object valueOrFallback(Map<String, Object> values, String key, Object fallback) {
        return values.containsKey(key) ? valueOrNull(values, key) : fallback;
    }

    private Object valueOrNull(Map<String, Object> values, String key) {
        if (!values.containsKey(key)) {
            return null;
        }
        Object value = values.get(key);
        if (value instanceof Collection<?>) {
            return values.get(key) == null ? null : values.get(key);
        }
        return value;
    }

    private Map<String, Object> firstMap(Object value) {
        List<Map<String, Object>> maps = maps(value);
        return maps.isEmpty() ? Map.of() : maps.get(0);
    }

    private Map<String, Object> mapOrNull(Object value) {
        Map<String, Object> result = map(value);
        return result.isEmpty() ? null : result;
    }

    private EvaluationExchangeSnapshot.StorageFilters storageFilters(
        Map<String, Object> plan,
        Map<String, Object> retrievalSnapshot,
        List<ChannelExecutionView> channelExecutions
    ) {
        Map<String, Object> plannedFilters = map(plan.get("metadataFilters"));
        Map<String, Object> projectedFilters = map(firstMap(retrievalSnapshot.get("executionRequests")).get("filters"));
        Set<String> executedChannels = channelExecutions == null
            ? Set.of()
            : channelExecutions.stream()
                .filter(Objects::nonNull)
                .map(ChannelExecutionView::getChannelType)
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean keywordConsumed = executedChannels.contains("keyword");
        boolean sectionConsumed = keywordConsumed || executedChannels.contains("vector");
        return new EvaluationExchangeSnapshot.StorageFilters(
            storageFilter(
                valueOrNull(plannedFilters, "documentNameHints"),
                valueOrNull(projectedFilters, "documentNameHints"),
                keywordConsumed,
                "ElasticsearchDocumentKeywordSearchGateway",
                "documentNameHints"
            ),
            storageFilter(
                valueOrNull(plannedFilters, "sectionPathHints"),
                valueOrNull(projectedFilters, "sectionPathHints"),
                sectionConsumed,
                "DocumentKnowledgeServiceImpl",
                "sectionPathHints"
            ),
            storageFilter(
                valueOrNull(plannedFilters, "yearHints"),
                valueOrNull(projectedFilters, "yearHints"),
                false,
                "NONE",
                "yearHints"
            ),
            storageFilter(
                valueOrNull(plan, "documentMetadataScope"),
                valueOrNull(firstMap(retrievalSnapshot.get("executionRequests")), "documentMetadataScope"),
                false,
                "KnowledgeBaseRetrievalScopeServiceImpl",
                "documentMetadataScope"
            )
        );
    }

    private EvaluationExchangeSnapshot.LayeredValue storageFilter(Object planned,
                                                                    Object projected,
                                                                    boolean consumed,
                                                                    String source,
                                                                    String fieldName) {
        String status;
        String consumedStage;
        String reason;
        if (consumed && projected != null) {
            status = "CONSUMED";
            consumedStage = "RAG_RETRIEVE";
            reason = "";
        }
        else if (projected != null || planned != null) {
            status = "NOT_CONSUMED";
            consumedStage = "";
            reason = "NO_STORAGE_CONSUMER_FACT:" + fieldName;
        }
        else {
            status = "NOT_AVAILABLE";
            consumedStage = "";
            reason = "MISSING_STORAGE_FILTER_FACT:" + fieldName;
        }
        return new EvaluationExchangeSnapshot.LayeredValue(
            planned,
            projected,
            consumedStage,
            status,
            source,
            reason
        );
    }

    private EvaluationExchangeSnapshot.QueryHints queryHints(
        Map<String, Object> plan,
        Map<String, Object> retrievalSnapshot,
        List<ChannelExecutionView> channelExecutions
    ) {
        Map<String, Object> plannedFilters = map(plan.get("metadataFilters"));
        Map<String, Object> projectedFilters = map(firstMap(retrievalSnapshot.get("executionRequests")).get("filters"));
        Object planned = valueOrNull(plannedFilters, "entityHints");
        Object projected = valueOrNull(projectedFilters, "entityHints");
        ChannelExecutionView graphExecution = channelExecutions == null
            ? null
            : channelExecutions.stream()
                .filter(Objects::nonNull)
                .filter(execution -> "graph-rag".equalsIgnoreCase(safe(execution.getChannelType())))
                .findFirst()
                .orElse(null);
        Map<String, Object> observation = graphExecution == null
            ? Map.of()
            : redactor.sanitizeMap(map(graphExecution.getConfigSnapshot()));
        boolean observed = graphExecution != null && observation.containsKey("entityHintsArrived");
        boolean arrived = Boolean.TRUE.equals(observation.get("entityHintsArrived"));
        String status = observed && arrived
            ? "CONSUMED"
            : projected != null ? "NOT_CONSUMED"
            : planned != null ? "ADVISORY" : "NOT_AVAILABLE";
        String consumedStage = "CONSUMED".equals(status) ? "RAG_RETRIEVE" : "";
        EvaluationExchangeSnapshot.LayeredValue entityHints = new EvaluationExchangeSnapshot.LayeredValue(
            planned,
            projected,
            consumedStage,
            status,
            "RetrievalPlan.metadataFilters.entityHints",
            observed ? text(observation.get("fallbackReason"), "") : "MISSING_GRAPH_RAG_CONSUMER_OBSERVATION"
        );
        EvaluationExchangeSnapshot.GraphRagObservation graphObservation = new EvaluationExchangeSnapshot.GraphRagObservation(
            arrived,
            strings(observation.get("receivedEntityHints")),
            strings(observation.get("usedEntitySeeds")),
            Boolean.TRUE.equals(observation.get("fallbackOccurred")),
            text(observation.get("fallbackReason"), observed ? "NONE" : ""),
            strings(observation.get("resultSources")),
            consumedStage,
            observed ? status : "NOT_AVAILABLE"
        );
        return new EvaluationExchangeSnapshot.QueryHints(entityHints, graphObservation);
    }

    private EvaluationExchangeSnapshot.EvidenceApplicability evidenceApplicability(
        Map<String, Object> plan,
        Map<String, Object> retrievalSnapshot
    ) {
        Map<String, Object> applicabilityPlan = map(plan.get("evidenceApplicabilityPlan"));
        List<Map<String, Object>> observations = maps(retrievalSnapshot.get("references")).stream()
            .filter(reference -> StrUtil.isNotBlank(text(reference.get("evidenceApplicabilityStatus"), "")))
            .map(reference -> {
                Map<String, Object> observation = new LinkedHashMap<>();
                observation.put("citationIdentity", text(reference.get("citationIdentity"), ""));
                observation.put("status", text(reference.get("evidenceApplicabilityStatus"), ""));
                observation.put("reason", text(reference.get("evidenceApplicabilityReason"), ""));
                return Map.copyOf(observation);
            })
            .toList();
        boolean consumed = !observations.isEmpty();
        return new EvaluationExchangeSnapshot.EvidenceApplicability(
            applicabilityValue(applicabilityPlan, "authorization", consumed),
            applicabilityValue(applicabilityPlan, "targetEntities", consumed),
            applicabilityValue(applicabilityPlan, "excludedEntities", consumed),
            applicabilityValue(applicabilityPlan, "confidence", false),
            applicabilityValue(applicabilityPlan, "source", consumed),
            applicabilityValue(applicabilityPlan, "reason", consumed),
            observations
        );
    }

    private EvaluationExchangeSnapshot.LayeredValue applicabilityValue(Map<String, Object> plan,
                                                                        String field,
                                                                        boolean consumed) {
        Object planned = valueOrNull(plan, field);
        if (planned == null) {
            return new EvaluationExchangeSnapshot.LayeredValue(
                null,
                null,
                "",
                "NOT_AVAILABLE",
                "EvidenceApplicabilityPlan",
                "MISSING_EVIDENCE_APPLICABILITY_FACT:" + field
            );
        }
        return new EvaluationExchangeSnapshot.LayeredValue(
            planned,
            consumed ? planned : null,
            consumed ? "FINAL_EVIDENCE_POLICY" : "",
            consumed ? "CONSUMED" : "ADVISORY",
            "EvidenceApplicabilityPlan",
            consumed ? "" : "MISSING_FINAL_EVIDENCE_CONSUMER_OBSERVATION"
        );
    }

    private EvaluationExchangeSnapshot.Retrieval retrieval(EvaluationExchangeSnapshotFacts facts,
                                                            Map<String, Object> retrievalSnapshot,
                                                            Map<String, Object> plan) {
        List<Map<String, Object>> requests = maps(retrievalSnapshot.get("executionRequests")).stream()
            .map(redactor::sanitizeMap)
            .toList();
        return new EvaluationExchangeSnapshot.Retrieval(
            new EvaluationExchangeSnapshot.Plan(
                text(plan.get("chatMode"), ""),
                text(plan.get("primaryIntent"), ""),
                text(plan.get("scopeMode"), ""),
                text(map(plan.get("questionPlan")).get("normalizedQuery"), normalizedInput(facts, plan)),
                enabledChannels(plan.get("channels")),
                integer(plan.get("candidateWindow")),
                integer(plan.get("rerankWindow")),
                integer(plan.get("finalEvidenceBudget")),
                strings(plan.get("reasons"))
            ),
            requests,
            facts.channelExecutions().stream().filter(Objects::nonNull).map(this::channel).toList(),
            facts.retrievalResults().stream().filter(Objects::nonNull).map(this::candidate).toList()
        );
    }

    private EvaluationExchangeSnapshot.ChannelExecution channel(ChannelExecutionView view) {
        return new EvaluationExchangeSnapshot.ChannelExecution(
            safe(view.getTraceId()),
            view.getSubQuestionIndex(),
            redactor.redactText(view.getSubQuestion()),
            safe(view.getChannelType()),
            view.getExecutionState(),
            view.getRecalledCount(),
            view.getAcceptedCount(),
            view.getFinalSelectedCount(),
            redactor.sanitizeMap(view.getConfigSnapshot()),
            redactor.redactText(view.getErrorMessage())
        );
    }

    private EvaluationExchangeSnapshot.Candidate candidate(RetrievalResultView view) {
        return new EvaluationExchangeSnapshot.Candidate(
            safe(view.getCandidateId()),
            safe(view.getChannelType()),
            view.getChannelRank(),
            view.getRrfRank(),
            view.getFinalRank(),
            view.getOriginalScore(),
            view.getHybridScore(),
            view.getRerankScore(),
            safe(view.getRankFeature()),
            view.isSelected(),
            safe(view.getSelectionReason()),
            safe(view.getFilteredReason()),
            view.getDocumentId(),
            view.getChunkId(),
            view.getParentBlockId(),
            redactor.redactText(view.getSectionPath()),
            redactor.redactText(view.getChunkTextPreview()),
            safe(view.getContextIdentity()),
            safe(view.getCitationIdentity()),
            safe(view.getCitationEvidenceType()),
            view.isContextOnly(),
            view.isSourceEvidenceResolved()
        );
    }

    private EvaluationExchangeSnapshot.Evidence evidence(EvaluationExchangeSnapshotFacts facts) {
        List<String> source = facts.retrievalResults().stream()
            .filter(Objects::nonNull)
            .filter(RetrievalResultView::isSelected)
            .filter(item -> !item.isContextOnly() && item.isSourceEvidenceResolved())
            .map(RetrievalResultView::getCitationIdentity)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        List<String> context = facts.retrievalResults().stream()
            .filter(Objects::nonNull)
            .filter(RetrievalResultView::isContextOnly)
            .map(RetrievalResultView::getContextIdentity)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        return new EvaluationExchangeSnapshot.Evidence(source, context);
    }

    private EvaluationExchangeSnapshot.Prompt prompt(Map<String, Object> snapshot, String schemaVersion) {
        List<Map<String, Object>> manifest = maps(snapshot.get("referenceManifest")).stream()
            .map(redactor::sanitizeMap)
            .toList();
        List<String> reused = manifest.stream()
            .filter(item -> "PROMPT_REUSED_SOURCE".equals(text(item.get("disposition"), "")))
            .map(item -> text(item.get("identity"), ""))
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        List<EvaluationExchangeSnapshot.PromptEvidence> renderedEvidence =
            EvaluationExchangeSnapshot.SCHEMA_VERSION_V2.equals(schemaVersion)
                || EvaluationExchangeSnapshot.SCHEMA_VERSION_V3.equals(schemaVersion)
                ? promptEvidence(snapshot)
                : List.of();
        return new EvaluationExchangeSnapshot.Prompt(
            text(snapshot.get("schemaVersion"), ""),
            manifest,
            strings(snapshot.get("renderedSourceIdentities")),
            reused,
            strings(snapshot.get("renderedContextIdentities")),
            renderedEvidence
        );
    }

    private List<EvaluationExchangeSnapshot.PromptEvidence> promptEvidence(Map<String, Object> snapshot) {
        return maps(snapshot.get("renderedSourceEvidence")).stream()
            .map(this::promptEvidenceItem)
            .filter(Objects::nonNull)
            .toList();
    }

    private EvaluationExchangeSnapshot.PromptEvidence promptEvidenceItem(Map<String, Object> item) {
        String identity = text(item.get("identity"), "");
        if (identity.isBlank()) {
            return null;
        }
        String redactedText = redactor.redactText(text(item.get("text"), ""));
        return new EvaluationExchangeSnapshot.PromptEvidence(
            identity,
            text(item.get("referenceId"), ""),
            text(item.get("sourceType"), ""),
            text(item.get("evidenceType"), ""),
            positiveLong(item.get("documentId")),
            text(item.get("documentContentVersion"), ""),
            redactedText,
            sha256Text(redactedText)
        );
    }

    private String sha256Text(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private EvaluationExchangeSnapshot.Citation citation(Map<String, Object> budget,
                                                          Map<String, Object> binding) {
        List<String> eligible = strings(binding.get("retrievedSourceIdentities"));
        if (eligible.isEmpty()) {
            eligible = strings(binding.get("renderedSourceIdentities"));
        }
        if (eligible.isEmpty()) {
            eligible = strings(budget.get("explicitCitationEligibleIdentities"));
        }
        return new EvaluationExchangeSnapshot.Citation(
            text(binding.get("schemaVersion"), ""),
            sanitizedMaps(binding.get("parsedTokens")),
            eligible,
            strings(binding.get("explicitCitationIdentities")),
            sanitizedMaps(binding.get("bindings")),
            sanitizedMaps(binding.get("rejectedTokens")),
            text(binding.get("conservationStatus"), binding.isEmpty() ? "NOT_AVAILABLE" : "UNKNOWN")
        );
    }

    private EvaluationExchangeSnapshot.Archive archive(EvaluationExchangeSnapshotFacts facts,
                                                       Map<String, Object> citationSnapshot) {
        List<String> retrieved = facts.archiveReferences().stream()
            .filter(Objects::nonNull)
            .filter(reference -> !reference.isContextOnly())
            .map(reference -> {
                String identity = reference.getCitationIdentity();
                return StrUtil.isNotBlank(identity) ? identity : reference.uniqueKey();
            })
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        List<String> explicit = strings(citationSnapshot.get("explicitCitationIdentities"));
        if (explicit.isEmpty()) {
            explicit = strings(citationSnapshot.get("sourceSnapshotIdentities"));
        }
        return new EvaluationExchangeSnapshot.Archive(
            facts.status().name(),
            redactor.redactText(facts.errorMessage()),
            explicit,
            retrieved,
            explicit
        );
    }

    private List<String> retrievedIdentities(Map<String, Object> citationSnapshot, List<String> promptRendered) {
        List<String> retrieved = strings(citationSnapshot.get("retrievedSourceIdentities"));
        if (retrieved.isEmpty()) {
            retrieved = strings(citationSnapshot.get("renderedSourceIdentities"));
        }
        if (retrieved.isEmpty()) {
            return promptRendered;
        }
        return retrieved;
    }

    private EvaluationExchangeSnapshot.Conservation conservation(List<String> promptEligible,
                                                                  List<String> bound,
                                                                  List<String> citationSnapshot,
                                                                  List<String> archive,
                                                                  List<String> finalizeIdentities,
                                                                  List<String> observedSelected,
                                                                  List<String> retrieved,
                                                                  List<String> archiveRetrieved,
                                                                  List<String> finalizeRetrieved,
                                                                  boolean finalizeHasRetrieved,
                                                                  Map<String, ConversationTraceStageView> stages) {
        List<String> reasons = new ArrayList<>();
        if (!stages.containsKey("EVIDENCE_BUDGET")) {
            reasons.add("MISSING_PROMPT_MANIFEST");
        }
        if (!stages.containsKey("CITATION_BINDING")) {
            reasons.add("MISSING_CITATION_BINDING");
        }
        if (!stages.containsKey("FINALIZE")) {
            reasons.add("MISSING_FINALIZE_STAGE");
        }
        if (!new LinkedHashSet<>(promptEligible).containsAll(bound)) {
            reasons.add("BOUND_IDENTITY_OUTSIDE_PROMPT_ELIGIBILITY");
        }
        if (!new LinkedHashSet<>(retrieved).containsAll(bound)) {
            reasons.add("BOUND_IDENTITY_OUTSIDE_RETRIEVED_SOURCES");
        }
        if (!retrieved.equals(promptEligible)) {
            reasons.add("RETRIEVED_SOURCE_PROMPT_MISMATCH");
        }
        if (!bound.equals(citationSnapshot)) {
            reasons.add("CITATION_SOURCE_SNAPSHOT_MISMATCH");
        }
        if (!bound.equals(archive)) {
            reasons.add("ARCHIVE_SOURCE_SNAPSHOT_MISMATCH");
        }
        if (!bound.equals(finalizeIdentities)) {
            reasons.add("FINALIZE_SOURCE_SNAPSHOT_MISMATCH");
        }
        if (!archiveRetrieved.equals(retrieved)) {
            reasons.add("ARCHIVE_RETRIEVED_SOURCE_MISMATCH");
        }
        if (finalizeHasRetrieved && !finalizeRetrieved.equals(retrieved)) {
            reasons.add("FINALIZE_RETRIEVED_SOURCE_MISMATCH");
        }
        if (!new LinkedHashSet<>(observedSelected).containsAll(promptEligible)) {
            reasons.add("PROMPT_SOURCE_NOT_IN_OBSERVED_SELECTION");
        }
        boolean missing = reasons.stream().anyMatch(reason -> reason.startsWith("MISSING_"));
        String status = reasons.isEmpty() ? "CONSERVED" : missing ? "INCOMPLETE" : "VIOLATED";
        return new EvaluationExchangeSnapshot.Conservation(
            promptEligible,
            bound,
            citationSnapshot,
            archive,
            finalizeIdentities,
            observedSelected,
            status,
            List.copyOf(reasons),
            retrieved,
            archiveRetrieved,
            finalizeRetrieved
        );
    }

    private EvaluationExchangeSnapshot.Provenance provenance(EvaluationExchangeSnapshotFacts facts,
                                                              EvaluationExchangeSnapshot.Scope scope,
                                                              EvaluationExchangeSnapshot.Prompt prompt) {
        Map<String, Object> config = readConfig(facts.retrievalConfigSnapshotJson());
        ChatDebugTrace debugTrace = facts.debugTrace();
        List<EvaluationExchangeSnapshot.Model> models = debugTrace == null || debugTrace.getModelUsageTraces() == null
            ? List.of()
            : debugTrace.getModelUsageTraces().stream()
                .filter(Objects::nonNull)
                .map(this::model)
                .toList();
        return new EvaluationExchangeSnapshot.Provenance(
            debugTrace == null ? "" : safe(debugTrace.getCodeCommit()),
            safe(properties.getPromptVersion()),
            models,
            config,
            sha256(config),
            prompt.renderedSourceEvidence().stream()
                .filter(evidence -> evidence.documentId() != null && !evidence.documentContentVersion().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                    evidence -> String.valueOf(evidence.documentId()),
                    EvaluationExchangeSnapshot.PromptEvidence::documentContentVersion,
                    (left, right) -> left,
                    LinkedHashMap::new
                )),
            scope.taskIds()
        );
    }

    private EvaluationExchangeSnapshot.Model model(ChatModelUsageTrace trace) {
        return new EvaluationExchangeSnapshot.Model(
            safe(trace.getStageName()),
            safe(trace.getProvider()),
            safe(trace.getModel()),
            safe(trace.getStatus())
        );
    }

    private List<EvaluationExchangeSnapshot.Readiness> readiness(EvaluationExchangeSnapshotFacts facts,
                                                                 Map<String, ConversationTraceStageView> stages,
                                                                 EvaluationExchangeSnapshot.Prompt prompt,
                                                                 EvaluationExchangeSnapshot.Citation citation,
                                                                 EvaluationExchangeSnapshot.Conservation conservation,
                                                                 EvaluationExchangeSnapshot.Provenance provenance,
                                                                 Map<String, Object> plan,
                                                                 EvaluationExchangeSnapshot.QueryHints queryHints) {
        boolean graphHintProjectedButNotConsumed = queryHints != null
            && queryHints.entityHints() != null
            && "NOT_CONSUMED".equals(queryHints.entityHints().consumptionStatus())
            && queryHints.entityHints().projected() instanceof Collection<?> projectedHints
            && !projectedHints.isEmpty();
        return EvaluationContractReadiness.evaluate(new EvaluationContractReadiness.Request(
            facts.factErrors(),
            facts.createdAt() != null,
            facts.terminalAt() != null,
            conservation.status(),
            conservation.reasons(),
            !provenance.codeCommit().isBlank(),
            !provenance.promptVersion().isBlank(),
            !provenance.actualModels().isEmpty(),
            !provenance.effectiveConfig().isEmpty(),
            !provenance.documentContentVersions().isEmpty(),
            !plan.isEmpty(),
            !facts.channelExecutions().isEmpty(),
            graphHintProjectedButNotConsumed,
            facts.status() == null ? "" : facts.status().name(),
            StrUtil.isNotBlank(facts.question()),
            StrUtil.isNotBlank(facts.answer()),
            !prompt.manifest().isEmpty(),
            !prompt.renderedSourceIdentities().isEmpty(),
            stages.containsKey("CITATION_BINDING"),
            citation.eligibleIdentities() != null && !citation.eligibleIdentities().isEmpty()
        ));
    }

    private EvaluationExchangeSnapshot.Diagnostics diagnostics(boolean included,
                                                               List<ConversationTraceStageView> stages) {
        if (!included) {
            return new EvaluationExchangeSnapshot.Diagnostics(List.of());
        }
        return new EvaluationExchangeSnapshot.Diagnostics(stages.stream()
            .filter(Objects::nonNull)
            .map(stage -> new EvaluationExchangeSnapshot.Stage(
                safe(stage.getStageCode()),
                safe(stage.getStageState()),
                text(snapshot(stage).get("schemaVersion"), "")
            ))
            .toList());
    }

    private String terminalOutcome(ChatTurnStatus status,
                                   EvaluationExchangeSnapshot.Prompt prompt,
                                   Map<String, ConversationTraceStageView> stages) {
        if (status == ChatTurnStatus.COMPLETED
            && stages.containsKey("RAG_RETRIEVE")
            && stages.containsKey("EVIDENCE_BUDGET")
            && prompt.renderedSourceIdentities().isEmpty()) {
            return "NO_EVIDENCE";
        }
        return status.name();
    }

    private String normalizedInput(EvaluationExchangeSnapshotFacts facts, Map<String, Object> plan) {
        String planQuery = text(map(plan.get("questionPlan")).get("normalizedQuery"), "");
        if (!planQuery.isBlank()) {
            return planQuery;
        }
        return facts.debugTrace() == null ? "" : safe(facts.debugTrace().getRetrievalQuestion());
    }

    private Map<String, ConversationTraceStageView> latestStageByCode(List<ConversationTraceStageView> stages) {
        Map<String, ConversationTraceStageView> result = new LinkedHashMap<>();
        for (ConversationTraceStageView stage : stages) {
            if (stage != null && StrUtil.isNotBlank(stage.getStageCode())) {
                result.put(stage.getStageCode(), stage);
            }
        }
        return result;
    }

    private Map<String, Object> snapshot(ConversationTraceStageView stage) {
        return stage == null ? Map.of() : redactor.sanitizeMap(stage.getSnapshot());
    }

    private List<Map<String, Object>> sanitizedMaps(Object value) {
        return maps(value).stream().map(redactor::sanitizeMap).toList();
    }

    private Map<String, Object> readConfig(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        try {
            return redactor.sanitizeMap(objectMapper.readValue(json, MAP_TYPE));
        }
        catch (Exception exception) {
            return Map.of("invalidConfigSnapshot", true);
        }
    }

    private String sha256(Map<String, Object> config) {
        try {
            byte[] canonical = objectMapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(config)
                .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Cannot hash sanitized evaluation configuration", exception);
        }
    }

    private List<String> enabledChannels(Object value) {
        return maps(value).stream()
            .filter(channel -> Boolean.TRUE.equals(channel.get("enabled")))
            .map(channel -> text(channel.get("channelName"), ""))
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().map(this::map).filter(item -> !item.isEmpty()).toList();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private List<Long> longs(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().map(this::positiveLong).filter(Objects::nonNull).toList();
    }

    private Long positiveLong(Object value) {
        try {
            long parsed = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
            return parsed > 0 ? parsed : null;
        }
        catch (RuntimeException exception) {
            return null;
        }
    }

    private int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? safe(fallback) : String.valueOf(value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
