package org.smartledge.ai.chatagent.rag.model;

import java.util.Objects;

/** One final-evidence-policy decision for one physical Source candidate. */
public record FinalEvidenceDecision(
    int candidateIndex,
    String candidateId,
    String citationIdentity,
    Owner owner,
    Disposition disposition,
    Reason reason,
    Integer finalRank,
    double relevanceScore,
    ScoreSource scoreSource,
    ScoreProvenance scoreProvenance,
    double policyScore,
    String applicabilityStatus,
    String applicabilityReason
) {

    public FinalEvidenceDecision {
        if (candidateIndex < 0) {
            throw new IllegalArgumentException("candidateIndex must be non-negative");
        }
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId is required");
        }
        citationIdentity = citationIdentity == null ? "" : citationIdentity;
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(scoreSource, "scoreSource");
        Objects.requireNonNull(scoreProvenance, "scoreProvenance");
        applicabilityStatus = applicabilityStatus == null ? "" : applicabilityStatus;
        applicabilityReason = applicabilityReason == null ? "" : applicabilityReason;
        if (disposition == Disposition.SELECTED && (finalRank == null || finalRank <= 0)) {
            throw new IllegalArgumentException("selected decision requires a positive finalRank");
        }
        if (disposition == Disposition.FILTERED && finalRank != null) {
            throw new IllegalArgumentException("filtered decision must not have a finalRank");
        }
        if (!candidateId.equals(scoreProvenance.sourceCandidateId())) {
            throw new IllegalArgumentException("score provenance must belong to the decision candidate");
        }
    }

    public enum Disposition {
        SELECTED,
        FILTERED
    }

    public enum Owner {
        FINAL_EVIDENCE_POLICY
    }

    public enum Reason {
        INCLUDED_BY_FINAL_EVIDENCE_POLICY,
        INCLUDED_BY_STRUCTURE_NAVIGATION_REQUIREMENT,
        FILTERED_NOT_CITATION_CAPABLE,
        FILTERED_NOT_APPLICABLE,
        FILTERED_BELOW_CONFIDENCE,
        FILTERED_DUPLICATE_SOURCE_IDENTITY,
        FILTERED_BY_FINAL_EVIDENCE_BUDGET
    }

    public enum ScoreSource {
        RERANK_SCORE,
        HYBRID_SCORE,
        SCORE,
        DOCUMENT_SCORE,
        NONE
    }

    public record ScoreProvenance(
        Kind kind,
        Stage stage,
        String sourceCandidateId,
        Normalization normalization,
        RerankStatus rerankStatus
    ) {

        public ScoreProvenance {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(stage, "stage");
            if (sourceCandidateId == null || sourceCandidateId.isBlank()) {
                throw new IllegalArgumentException("sourceCandidateId is required");
            }
            Objects.requireNonNull(normalization, "normalization");
            Objects.requireNonNull(rerankStatus, "rerankStatus");
        }
    }

    public enum Kind {
        DIRECT_CANDIDATE_SCORE,
        ZERO_FALLBACK
    }

    public enum Stage {
        RERANK,
        FUSION,
        RETRIEVAL,
        FINAL_EVIDENCE_POLICY
    }

    public enum Normalization {
        NONE,
        NON_FINITE_TO_ZERO,
        MISSING_TO_ZERO
    }

    public enum RerankStatus {
        SUCCESS,
        FAILED,
        NOT_REQUESTED
    }
}
