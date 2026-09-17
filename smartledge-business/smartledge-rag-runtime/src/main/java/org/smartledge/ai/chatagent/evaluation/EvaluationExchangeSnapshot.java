package org.smartledge.ai.chatagent.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Stable formal projection consumed by external evaluation tooling. */
public record EvaluationExchangeSnapshot(
    String schemaVersion,
    Exchange exchange,
    Input input,
    Scope scope,
    Retrieval retrieval,
    Evidence evidence,
    Prompt prompt,
    Answer answer,
    Citation citation,
    Archive archive,
    Conservation conservation,
    Provenance provenance,
    List<Readiness> readiness,
    Diagnostics diagnostics,
    @JsonInclude(JsonInclude.Include.NON_NULL) HardScope hardScope,
    @JsonInclude(JsonInclude.Include.NON_NULL) StorageFilters storageFilters,
    @JsonInclude(JsonInclude.Include.NON_NULL) QueryHints queryHints,
    @JsonInclude(JsonInclude.Include.NON_NULL) EvidenceApplicability evidenceApplicability
) {

    public static final String SCHEMA_VERSION = "evaluation-exchange-snapshot.v1";
    public static final String SCHEMA_VERSION_V2 = "evaluation-exchange-snapshot.v2";
    public static final String SCHEMA_VERSION_V3 = "evaluation-exchange-snapshot.v3";

    /**
     * Compatibility constructor for the immutable v1/v2 projection shape.
     *
     * <p>The layered fields are deliberately null for old schema versions so serializing an
     * existing v1/v2 snapshot remains compatible with its frozen contract.</p>
     */
    public EvaluationExchangeSnapshot(String schemaVersion,
                                      Exchange exchange,
                                      Input input,
                                      Scope scope,
                                      Retrieval retrieval,
                                      Evidence evidence,
                                      Prompt prompt,
                                      Answer answer,
                                      Citation citation,
                                      Archive archive,
                                      Conservation conservation,
                                      Provenance provenance,
                                      List<Readiness> readiness,
                                      Diagnostics diagnostics) {
        this(schemaVersion, exchange, input, scope, retrieval, evidence, prompt, answer, citation,
            archive, conservation, provenance, readiness, diagnostics, null, null, null, null);
    }

    public record Exchange(
        long exchangeId,
        String conversationId,
        String terminalStatus,
        String terminalOutcome,
        Instant createdAt,
        Instant terminalAt
    ) {
    }

    public record Input(String originalQuestion, String normalizedInput) {
    }

    public record Scope(
        String mode,
        List<Long> knowledgeBaseIds,
        List<Long> allowedDocumentIds,
        List<Long> documentIds,
        List<Long> taskIds
    ) {
    }

    public record Retrieval(
        Plan plan,
        List<Map<String, Object>> executionRequests,
        List<ChannelExecution> channelExecutions,
        List<Candidate> candidates
    ) {
    }

    public record Plan(
        String chatMode,
        String primaryIntent,
        String scopeMode,
        String normalizedQuery,
        List<String> enabledChannels,
        int candidateWindow,
        int rerankWindow,
        int finalEvidenceBudget,
        List<String> reasons
    ) {
    }

    public record ChannelExecution(
        String traceId,
        int subQuestionIndex,
        String subQuestion,
        String channelType,
        int executionState,
        int recalledCount,
        int acceptedCount,
        int finalSelectedCount,
        Map<String, Object> requestSnapshot,
        String errorMessage
    ) {
    }

    public record Candidate(
        String candidateId,
        String channelType,
        Integer channelRank,
        Integer fusionRank,
        Integer finalRank,
        BigDecimal originalScore,
        BigDecimal fusionScore,
        BigDecimal rerankScore,
        String rankFeature,
        boolean selected,
        String selectionReason,
        String filteredReason,
        Long documentId,
        Long chunkId,
        Long parentBlockId,
        String sectionPath,
        String textPreview,
        String contextIdentity,
        String citationIdentity,
        String citationEvidenceType,
        boolean contextOnly,
        boolean sourceEvidenceResolved
    ) {
    }

    public record Evidence(List<String> sourceIdentities, List<String> contextOnlyIdentities) {
    }

    public record Prompt(
        String manifestSchemaVersion,
        List<Map<String, Object>> manifest,
        List<String> renderedSourceIdentities,
        List<String> reusedSourceIdentities,
        List<String> renderedContextIdentities,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PromptEvidence> renderedSourceEvidence
    ) {
        public Prompt(String manifestSchemaVersion,
                      List<Map<String, Object>> manifest,
                      List<String> renderedSourceIdentities,
                      List<String> reusedSourceIdentities,
                      List<String> renderedContextIdentities) {
            this(manifestSchemaVersion, manifest, renderedSourceIdentities, reusedSourceIdentities,
                renderedContextIdentities, List.of());
        }
    }

    public record PromptEvidence(
        String identity,
        String referenceId,
        String sourceType,
        String evidenceType,
        Long documentId,
        String documentContentVersion,
        String text,
        String textHash
    ) {
    }

    public record Answer(String text) {
    }

    public record Citation(
        String bindingSchemaVersion,
        List<Map<String, Object>> parsedTokens,
        List<String> eligibleIdentities,
        List<String> boundIdentities,
        List<Map<String, Object>> bindings,
        List<Map<String, Object>> rejectedTokens,
        String bindingResult
    ) {
    }

    public record Archive(
        String terminalStatus,
        String terminalReason,
        List<String> sourceSnapshotIdentities,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> retrievedSourceIdentities,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> explicitCitationIdentities
    ) {
        public Archive(String terminalStatus,
                       String terminalReason,
                       List<String> sourceSnapshotIdentities) {
            this(
                terminalStatus,
                terminalReason,
                sourceSnapshotIdentities,
                List.of(),
                sourceSnapshotIdentities == null ? List.of() : sourceSnapshotIdentities
            );
        }
    }

    public record Conservation(
        List<String> promptEligibleIdentities,
        List<String> boundCitationIdentities,
        List<String> citationSourceSnapshotIdentities,
        List<String> archiveSourceSnapshotIdentities,
        List<String> finalizeSourceSnapshotIdentities,
        List<String> observedSelectedSourceIdentities,
        String status,
        List<String> reasons,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> retrievedSourceIdentities,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> archiveRetrievedSourceIdentities,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> finalizeRetrievedSourceIdentities
    ) {
        public Conservation(List<String> promptEligibleIdentities,
                            List<String> boundCitationIdentities,
                            List<String> citationSourceSnapshotIdentities,
                            List<String> archiveSourceSnapshotIdentities,
                            List<String> finalizeSourceSnapshotIdentities,
                            List<String> observedSelectedSourceIdentities,
                            String status,
                            List<String> reasons) {
            this(
                promptEligibleIdentities,
                boundCitationIdentities,
                citationSourceSnapshotIdentities,
                archiveSourceSnapshotIdentities,
                finalizeSourceSnapshotIdentities,
                observedSelectedSourceIdentities,
                status,
                reasons,
                List.of(),
                List.of(),
                List.of()
            );
        }
    }

    public record Provenance(
        String codeCommit,
        String promptVersion,
        List<Model> actualModels,
        Map<String, Object> effectiveConfig,
        String effectiveConfigHash,
        Map<String, String> documentContentVersions,
        List<Long> indexTaskIds
    ) {
    }

    public record Model(String stageName, String provider, String model, String status) {
    }

    public record Readiness(String family, boolean ready, List<String> reasons) {
    }

    public record Diagnostics(List<Stage> stages) {
    }

    public record Stage(String stageCode, String stageState, String snapshotSchemaVersion) {
    }

    /** One planned/projected value and its observed consumer state. */
    public record LayeredValue(
        Object planned,
        Object projected,
        String consumedStage,
        String consumptionStatus,
        String source,
        String reason
    ) {
        public LayeredValue {
            consumedStage = consumedStage == null ? "" : consumedStage;
            consumptionStatus = consumptionStatus == null ? "NOT_AVAILABLE" : consumptionStatus;
            source = source == null ? "" : source;
            reason = reason == null ? "" : reason;
        }
    }

    /** Hard scope facts owned by knowledge-base selection and the RetrievalPlan. */
    public record HardScope(
        LayeredValue knowledgeBaseIds,
        LayeredValue allowedDocumentIds,
        LayeredValue executionDocumentIds,
        LayeredValue taskIds,
        LayeredValue scopeMode,
        LayeredValue routeAuthorization
    ) {
    }

    /** Storage predicates projected to storage-facing requests, never query hints. */
    public record StorageFilters(
        LayeredValue documentNameHints,
        LayeredValue sectionPathHints,
        LayeredValue yearHints,
        LayeredValue documentMetadataScope
    ) {
    }

    /** Query-time advisory hints and the GraphRAG consumer observation. */
    public record QueryHints(
        LayeredValue entityHints,
        GraphRagObservation graphRagObservation
    ) {
    }

    public record GraphRagObservation(
        boolean entityHintsArrived,
        List<String> receivedEntityHints,
        List<String> usedEntitySeeds,
        boolean fallbackOccurred,
        String fallbackReason,
        List<String> resultSources,
        String consumedStage,
        String consumptionStatus
    ) {
        public GraphRagObservation {
            receivedEntityHints = receivedEntityHints == null ? List.of() : List.copyOf(receivedEntityHints);
            usedEntitySeeds = usedEntitySeeds == null ? List.of() : List.copyOf(usedEntitySeeds);
            resultSources = resultSources == null ? List.of() : List.copyOf(resultSources);
            fallbackReason = fallbackReason == null ? "" : fallbackReason;
            consumedStage = consumedStage == null ? "" : consumedStage;
            consumptionStatus = consumptionStatus == null ? "NOT_AVAILABLE" : consumptionStatus;
        }
    }

    /** Final-evidence applicability facts, kept outside retrieval scope and citation binding. */
    public record EvidenceApplicability(
        LayeredValue authorization,
        LayeredValue targetEntities,
        LayeredValue excludedEntities,
        LayeredValue confidence,
        LayeredValue source,
        LayeredValue reason,
        List<Map<String, Object>> candidateObservations
    ) {
        public EvidenceApplicability {
            candidateObservations = candidateObservations == null ? List.of() : List.copyOf(candidateObservations);
        }
    }
}
