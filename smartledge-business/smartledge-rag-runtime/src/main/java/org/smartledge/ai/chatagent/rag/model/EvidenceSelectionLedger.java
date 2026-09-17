package org.smartledge.ai.chatagent.rag.model;

import java.util.List;
import java.util.Objects;

/** Producer-owned Source partition, ranking, rerank, lineage, and final-policy ledger. */
public record EvidenceSelectionLedger(
    EvidenceLineage evidenceLineage,
    RerankExecution rerankExecution,
    List<SourceRankingWindowDecision> sourceRankingWindowDecisions,
    List<SourceRankingWindowCandidate> sourceRankingWindowCandidates,
    List<RerankRequestCandidate> rerankRequestCandidates,
    List<RerankResultCandidate> rerankResultCandidates,
    List<IdentityTransition> identityTransitions,
    List<FinalEvidenceDecision> finalEvidenceDecisions
) {

    public EvidenceSelectionLedger {
        Objects.requireNonNull(evidenceLineage, "evidenceLineage");
        Objects.requireNonNull(rerankExecution, "rerankExecution");
        sourceRankingWindowDecisions = copy(sourceRankingWindowDecisions);
        sourceRankingWindowCandidates = copy(sourceRankingWindowCandidates);
        rerankRequestCandidates = copy(rerankRequestCandidates);
        rerankResultCandidates = copy(rerankResultCandidates);
        identityTransitions = copy(identityTransitions);
        finalEvidenceDecisions = copy(finalEvidenceDecisions);
    }

    public static EvidenceSelectionLedger empty(boolean rerankRequested) {
        RerankExecution execution = rerankRequested
            ? new RerankExecution(true, false, RerankExecutionStatus.SKIPPED_EMPTY_SOURCE_WINDOW,
                RerankExecutionReason.EMPTY_SOURCE_WINDOW, RerankFailureStage.NONE)
            : new RerankExecution(false, false, RerankExecutionStatus.NOT_REQUESTED,
                RerankExecutionReason.PLAN_DISABLED, RerankFailureStage.NONE);
        return new EvidenceSelectionLedger(
            EvidenceLineage.empty(),
            execution,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record EvidenceLineage(
        List<String> recalledCandidateIds,
        List<String> acceptedCandidateIds,
        List<String> fusionWindowEvidenceIdentities,
        List<String> rerankInputEvidenceIdentities,
        List<String> contextDispositionCandidateIds,
        List<String> sourcePartitionCandidateIds,
        List<String> finalEvidencePolicyInputCandidateIds,
        List<String> budgetSelectedSourceIdentities
    ) {

        public EvidenceLineage {
            recalledCandidateIds = copy(recalledCandidateIds);
            acceptedCandidateIds = copy(acceptedCandidateIds);
            fusionWindowEvidenceIdentities = copy(fusionWindowEvidenceIdentities);
            rerankInputEvidenceIdentities = copy(rerankInputEvidenceIdentities);
            contextDispositionCandidateIds = copy(contextDispositionCandidateIds);
            sourcePartitionCandidateIds = copy(sourcePartitionCandidateIds);
            finalEvidencePolicyInputCandidateIds = copy(finalEvidencePolicyInputCandidateIds);
            budgetSelectedSourceIdentities = copy(budgetSelectedSourceIdentities);
        }

        public static EvidenceLineage empty() {
            return new EvidenceLineage(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );
        }
    }

    public record RerankExecution(
        boolean requested,
        boolean attempted,
        RerankExecutionStatus status,
        RerankExecutionReason reason,
        RerankFailureStage failureStage
    ) {

        public RerankExecution {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(failureStage, "failureStage");
            if (requested != (status != RerankExecutionStatus.NOT_REQUESTED)) {
                throw new IllegalArgumentException("requested must be a pure projection of NOT_REQUESTED status");
            }
            if (attempted != (status == RerankExecutionStatus.FAILED_AFTER_REQUEST
                || status == RerankExecutionStatus.SUCCESS)) {
                throw new IllegalArgumentException("attempted must match the transport handoff status");
            }
        }
    }

    public enum RerankExecutionStatus {
        NOT_REQUESTED,
        SKIPPED_EMPTY_SOURCE_WINDOW,
        FAILED_BEFORE_REQUEST,
        FAILED_AFTER_REQUEST,
        SUCCESS
    }

    public enum RerankExecutionReason {
        NONE,
        PLAN_DISABLED,
        EMPTY_SOURCE_WINDOW,
        WINDOW_BUILD_FAILED,
        PROVIDER_UNAVAILABLE,
        CIRCUIT_OPEN,
        INVALID_CONFIGURATION,
        CLIENT_INITIALIZATION_FAILED,
        REQUEST_VALIDATION_FAILED,
        TRANSPORT_ERROR,
        TIMEOUT,
        PROVIDER_ERROR,
        INVALID_RESPONSE,
        PARTIAL_RESULT,
        CANCELLED
    }

    public enum RerankFailureStage {
        NONE,
        WINDOW_BUILD,
        PRE_REQUEST,
        TRANSPORT,
        RESPONSE_VALIDATION
    }

    public record SourceRankingWindowDecision(
        String candidateId,
        int inputRank,
        Integer windowRank,
        int cap,
        WindowDisposition disposition,
        WindowReason reason,
        OrderingSource orderingSource
    ) {

        public SourceRankingWindowDecision {
            requireText(candidateId, "candidateId");
            if (inputRank <= 0 || cap <= 0) {
                throw new IllegalArgumentException("inputRank and cap must be positive");
            }
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(orderingSource, "orderingSource");
        }
    }

    public enum WindowDisposition {
        INCLUDED,
        FILTERED
    }

    public enum WindowReason {
        INCLUDED_IN_SOURCE_RANKING_WINDOW,
        FILTERED_BY_SOURCE_RANKING_WINDOW_CAP,
        FILTERED_BY_SOURCE_RANKING_WINDOW_BUILD_FAILURE
    }

    public enum OrderingSource {
        FUSION_INPUT_ORDER
    }

    public record SourceRankingWindowCandidate(
        String candidateId,
        String lineageIdentity,
        String citationIdentity,
        IdentityResolutionStatus identityResolutionStatus,
        CandidateLane lane
    ) {

        public SourceRankingWindowCandidate {
            requireText(candidateId, "candidateId");
            requireText(lineageIdentity, "lineageIdentity");
            citationIdentity = citationIdentity == null ? "" : citationIdentity;
            Objects.requireNonNull(identityResolutionStatus, "identityResolutionStatus");
            Objects.requireNonNull(lane, "lane");
        }
    }

    public enum CandidateLane {
        SOURCE
    }

    public enum IdentityResolutionStatus {
        CITATION_RESOLVED,
        CONTEXT_RESOLVED,
        CANDIDATE_FALLBACK
    }

    public record RerankRequestCandidate(String requestCandidateId, String candidateId) {

        public RerankRequestCandidate {
            requireText(requestCandidateId, "requestCandidateId");
            requireText(candidateId, "candidateId");
        }
    }

    public record RerankResultCandidate(
        String requestCandidateId,
        String candidateId,
        RerankResultStatus resultStatus,
        Double rerankScore,
        Integer rerankRank
    ) {

        public RerankResultCandidate {
            requireText(requestCandidateId, "requestCandidateId");
            requireText(candidateId, "candidateId");
            Objects.requireNonNull(resultStatus, "resultStatus");
            if (resultStatus == RerankResultStatus.UNUSABLE && (rerankScore != null || rerankRank != null)) {
                throw new IllegalArgumentException("UNUSABLE result must not expose a score or rank");
            }
        }
    }

    public enum RerankResultStatus {
        FINITE,
        NON_FINITE_TO_ZERO,
        UNUSABLE
    }

    public record IdentityTransition(
        String transitionId,
        List<CandidateEndpoint> fromCandidates,
        List<CandidateEndpoint> toCandidates,
        IdentityTransitionType transitionType,
        IdentityTransitionStage stage,
        int inputCardinality,
        int outputCardinality
    ) {

        public IdentityTransition {
            requireText(transitionId, "transitionId");
            fromCandidates = copy(fromCandidates);
            toCandidates = copy(toCandidates);
            Objects.requireNonNull(transitionType, "transitionType");
            Objects.requireNonNull(stage, "stage");
            if (inputCardinality != fromCandidates.size() || outputCardinality != toCandidates.size()) {
                throw new IllegalArgumentException("transition cardinality must match its endpoints");
            }
        }
    }

    public record CandidateEndpoint(
        String candidateId,
        String lineageIdentity,
        IdentityResolutionStatus identityResolutionStatus
    ) {

        public CandidateEndpoint {
            requireText(candidateId, "candidateId");
            requireText(lineageIdentity, "lineageIdentity");
            Objects.requireNonNull(identityResolutionStatus, "identityResolutionStatus");
        }
    }

    public enum IdentityTransitionType {
        FUSION_DEDUPLICATION,
        PARENT_ELEVATION,
        CONTEXT_EXPANSION
    }

    public enum IdentityTransitionStage {
        FUSION,
        PARENT_ELEVATION,
        CONTEXT_EXPANSION
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
