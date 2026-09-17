package org.smartledge.ai.chatagent.rag.service;

import org.smartledge.ai.chatagent.rag.model.EvidenceApplicabilityPlan;
import org.smartledge.ai.chatagent.rag.model.EvidenceApplicabilityResult;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision.Disposition;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision.Kind;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision.Normalization;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision.Owner;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision.Reason;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision.RerankStatus;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision.ScoreProvenance;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision.ScoreSource;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision.Stage;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.support.EvidenceCandidateIdentity;
import org.smartledge.ai.chatagent.rag.support.EvidenceIdentityResolver;
import org.smartledge.ai.chatagent.rag.support.EvidenceQualityFeatures;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The single Source-only final evidence selection authority. */
public final class FinalEvidenceSelectionPolicy {

    private final EvidenceApplicabilityService applicabilityService;

    public FinalEvidenceSelectionPolicy(EvidenceApplicabilityService applicabilityService) {
        this.applicabilityService = Objects.requireNonNull(applicabilityService, "applicabilityService");
    }

    public SelectionResult select(List<RetrievalDocument> sourceCandidates, RetrievalPlan plan) {
        List<RetrievalDocument> candidates = sourceCandidates == null ? List.of() : sourceCandidates;
        int budget = plan == null ? 0 : Math.max(plan.getFinalEvidenceBudget(), 0);
        EvidenceApplicabilityPlan applicabilityPlan = plan == null ? null : plan.getEvidenceApplicabilityPlan();

        List<EvaluatedCandidate> evaluated = new ArrayList<>(candidates.size());
        Map<Integer, FinalEvidenceDecision> decisions = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            EvaluatedCandidate candidate = evaluate(index, candidates.get(index), applicabilityPlan);
            evaluated.add(candidate);
            if (!candidate.quality().citationCapable()) {
                decisions.put(index, candidate.decision(Disposition.FILTERED, Reason.FILTERED_NOT_CITATION_CAPABLE, null));
            }
        }

        List<EvaluatedCandidate> representatives = deduplicateEligibleCandidates(evaluated, decisions);
        List<EvaluatedCandidate> selected = selectTopK(representatives, budget);
        Map<Integer, Integer> finalRanks = new LinkedHashMap<>();
        for (int index = 0; index < selected.size(); index++) {
            finalRanks.put(selected.get(index).inputIndex(), index + 1);
        }
        for (EvaluatedCandidate candidate : representatives) {
            Integer finalRank = finalRanks.get(candidate.inputIndex());
            decisions.put(candidate.inputIndex(), finalRank == null
                ? candidate.decision(Disposition.FILTERED, Reason.FILTERED_BY_FINAL_EVIDENCE_BUDGET, null)
                : candidate.decision(
                    Disposition.SELECTED,
                    Reason.INCLUDED_BY_FINAL_EVIDENCE_POLICY,
                    finalRank
                ));
        }

        return new SelectionResult(
            selected.stream().map(EvaluatedCandidate::document).toList(),
            evaluated.stream().map(candidate -> decisions.get(candidate.inputIndex())).toList()
        );
    }

    private EvaluatedCandidate evaluate(int inputIndex,
                                        RetrievalDocument document,
                                        EvidenceApplicabilityPlan applicabilityPlan) {
        EvidenceCandidateIdentity.ensure(document);
        String candidateId = EvidenceCandidateIdentity.candidateId(document);
        RerankStatus rerankStatus = resolveRerankStatus(document);
        Score score = resolveScore(document, candidateId, rerankStatus);
        EvidenceQualityFeatures quality = EvidenceQualityFeatures.resolve(document);
        EvidenceApplicabilityResult applicability = applicabilityService.evaluate(applicabilityPlan, document);
        String citationIdentity = EvidenceIdentityResolver.citationIdentityValue(document);
        double policyScore = quality.selectionPriority(score.value());
        if (!Double.isFinite(policyScore)) {
            policyScore = score.value();
        }
        return new EvaluatedCandidate(
            inputIndex,
            document,
            candidateId,
            citationIdentity,
            EvidenceIdentityResolver.sourceFamilyIdentityValue(document),
            documentId(document),
            structureSectionIdentities(document),
            score.value(),
            score.source(),
            score.provenance(),
            policyScore,
            quality,
            applicability
        );
    }

    private RerankStatus resolveRerankStatus(RetrievalDocument document) {
        Object value = document == null || document.getMetadata() == null
            ? null
            : document.getMetadata().get(DocumentKnowledgeMetadataKeys.RERANK_STATUS);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("Every final policy input requires an explicit rerank status");
        }
        try {
            return RerankStatus.valueOf(String.valueOf(value));
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown final policy rerank status: " + value, exception);
        }
    }

    private Score resolveScore(RetrievalDocument document, String candidateId, RerankStatus rerankStatus) {
        if (rerankStatus == RerankStatus.SUCCESS) {
            Score rerank = directScore(document, candidateId, DocumentKnowledgeMetadataKeys.RERANK_SCORE,
                ScoreSource.RERANK_SCORE, Stage.RERANK, rerankStatus);
            if (rerank == null) {
                throw new IllegalStateException("Successful rerank candidate is missing its direct rerank score");
            }
            return rerank;
        }
        Score hybrid = directScore(document, candidateId, DocumentKnowledgeMetadataKeys.HYBRID_SCORE,
            ScoreSource.HYBRID_SCORE, Stage.FUSION, rerankStatus);
        if (hybrid != null) {
            return hybrid;
        }
        Score metadata = directScore(document, candidateId, DocumentKnowledgeMetadataKeys.SCORE,
            ScoreSource.SCORE, Stage.FUSION, rerankStatus);
        if (metadata != null) {
            return metadata;
        }
        if (document != null && document.getScore() != null) {
            return directScore(document.getScore(), candidateId, ScoreSource.DOCUMENT_SCORE, Stage.RETRIEVAL, rerankStatus);
        }
        return new Score(
            0D,
            ScoreSource.NONE,
            new ScoreProvenance(Kind.ZERO_FALLBACK, Stage.FINAL_EVIDENCE_POLICY, candidateId,
                Normalization.MISSING_TO_ZERO, rerankStatus)
        );
    }

    private Score directScore(RetrievalDocument document,
                              String candidateId,
                              String key,
                              ScoreSource source,
                              Stage stage,
                              RerankStatus rerankStatus) {
        if (document == null || document.getMetadata() == null || !document.getMetadata().containsKey(key)) {
            return null;
        }
        Object value = document.getMetadata().get(key);
        Double number = number(value);
        return number == null ? null : directScore(number, candidateId, source, stage, rerankStatus);
    }

    private Score directScore(double value,
                              String candidateId,
                              ScoreSource source,
                              Stage stage,
                              RerankStatus rerankStatus) {
        boolean finite = Double.isFinite(value);
        return new Score(
            finite ? value : 0D,
            source,
            new ScoreProvenance(Kind.DIRECT_CANDIDATE_SCORE, stage, candidateId,
                finite ? Normalization.NONE : Normalization.NON_FINITE_TO_ZERO, rerankStatus)
        );
    }

    private List<EvaluatedCandidate> deduplicateEligibleCandidates(
        List<EvaluatedCandidate> candidates,
        Map<Integer, FinalEvidenceDecision> decisions) {
        Map<String, List<EvaluatedCandidate>> candidatesByIdentity = new LinkedHashMap<>();
        for (EvaluatedCandidate candidate : candidates) {
            if (!decisions.containsKey(candidate.inputIndex())) {
                candidatesByIdentity.computeIfAbsent(candidate.citationIdentity(), ignored -> new ArrayList<>()).add(candidate);
            }
        }

        List<EvaluatedCandidate> representatives = new ArrayList<>(candidatesByIdentity.size());
        Comparator<EvaluatedCandidate> priority = Comparator.comparingInt(EvaluatedCandidate::inputIndex);
        for (List<EvaluatedCandidate> duplicates : candidatesByIdentity.values()) {
            EvaluatedCandidate representative = duplicates.stream().min(priority).orElseThrow();
            representatives.add(representative);
            for (EvaluatedCandidate duplicate : duplicates) {
                if (duplicate.inputIndex() != representative.inputIndex()) {
                    decisions.put(duplicate.inputIndex(), duplicate.decision(
                        Disposition.FILTERED,
                        Reason.FILTERED_DUPLICATE_SOURCE_IDENTITY,
                        null
                    ));
                }
            }
        }
        return representatives;
    }

    private List<EvaluatedCandidate> selectTopK(List<EvaluatedCandidate> candidates, int budget) {
        if (budget <= 0 || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
            .sorted(Comparator.comparingInt(EvaluatedCandidate::inputIndex))
            .limit(budget)
            .toList();
    }

    private Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long documentId(RetrievalDocument document) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Object value = document.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Set<String> structureSectionIdentities(RetrievalDocument document) {
        if (document == null || document.getMetadata() == null) {
            return Set.of();
        }
        Set<String> identities = new LinkedHashSet<>();
        Long documentId = documentId(document);
        String documentScope = Objects.toString(documentId, "");
        Long structureNodeId = longMetadata(document, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID);
        if (structureNodeId != null) {
            identities.add("NODE:" + documentScope + ":" + structureNodeId);
        }
        String canonicalPath = textMetadata(document, DocumentKnowledgeMetadataKeys.CANONICAL_PATH);
        if (!canonicalPath.isBlank()) {
            identities.add("PATH:" + documentScope + ":" + canonicalPath);
        }
        String sectionPath = textMetadata(document, DocumentKnowledgeMetadataKeys.SECTION_PATH);
        if (!sectionPath.isBlank()) {
            identities.add("SECTION:" + documentScope + ":" + sectionPath);
        }
        return Set.copyOf(identities);
    }

    private Long longMetadata(RetrievalDocument document, String key) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Object value = document.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String textMetadata(RetrievalDocument document, String key) {
        if (document == null || document.getMetadata() == null) {
            return "";
        }
        Object value = document.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record SelectionResult(List<RetrievalDocument> selectedDocuments, List<FinalEvidenceDecision> decisions) {

        public SelectionResult {
            selectedDocuments = selectedDocuments == null ? List.of() : List.copyOf(selectedDocuments);
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }

    private record Score(double value, ScoreSource source, ScoreProvenance provenance) {
    }

    private record EvaluatedCandidate(
        int inputIndex,
        RetrievalDocument document,
        String candidateId,
        String citationIdentity,
        String sourceFamilyIdentity,
        Long documentId,
        Set<String> structureSectionIdentities,
        double relevanceScore,
        ScoreSource scoreSource,
        ScoreProvenance scoreProvenance,
        double policyScore,
        EvidenceQualityFeatures quality,
        EvidenceApplicabilityResult applicability
    ) {

        private FinalEvidenceDecision decision(Disposition disposition, Reason reason, Integer finalRank) {
            return new FinalEvidenceDecision(
                inputIndex,
                candidateId,
                citationIdentity,
                Owner.FINAL_EVIDENCE_POLICY,
                disposition,
                reason,
                finalRank,
                relevanceScore,
                scoreSource,
                scoreProvenance,
                policyScore,
                applicability == null ? "" : applicability.getStatus(),
                applicability == null ? "" : applicability.getReason()
            );
        }
    }
}
