package org.smartledge.ai.chatagent.rag.service;

import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.CandidateLane;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.OrderingSource;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.SourceRankingWindowCandidate;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.SourceRankingWindowDecision;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.WindowDisposition;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.WindowReason;
import org.smartledge.ai.chatagent.rag.support.EvidenceCandidateIdentity;
import org.smartledge.ai.chatagent.rag.support.EvidenceIdentityResolver;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The only Source evidence ranking window: a stable fusion-order prefix. */
public final class SourceRankingWindow {

    public Result build(List<RetrievalDocument> sourceCandidates, int cap) {
        if (cap <= 0) {
            throw new IllegalArgumentException("Source ranking window cap must be positive");
        }
        List<RetrievalDocument> sources = sourceCandidates == null ? List.of() : sourceCandidates;
        try {
            List<RetrievalDocument> snapshot = List.copyOf(sources);
            validateInputs(snapshot);
            return buildNormal(snapshot, cap);
        }
        catch (RuntimeException exception) {
            return buildFailure(sources, cap, exception);
        }
    }

    private void validateInputs(List<RetrievalDocument> sources) {
        Set<String> candidateIds = new LinkedHashSet<>();
        for (RetrievalDocument source : sources) {
            if (source == null) {
                throw new IllegalStateException("Source ranking input must not contain null candidates");
            }
            String candidateId = EvidenceCandidateIdentity.candidateId(source);
            EvidenceCandidateIdentity.lineageIdentity(source);
            EvidenceCandidateIdentity.identityResolutionStatus(source);
            if (!candidateIds.add(candidateId)) {
                throw new IllegalStateException("Source ranking input candidate IDs must be unique");
            }
        }
    }

    private Result buildNormal(List<RetrievalDocument> sources, int cap) {
        List<RetrievalDocument> included = new ArrayList<>(Math.min(sources.size(), cap));
        List<SourceRankingWindowDecision> decisions = new ArrayList<>(sources.size());
        List<SourceRankingWindowCandidate> candidates = new ArrayList<>(Math.min(sources.size(), cap));
        for (int index = 0; index < sources.size(); index++) {
            RetrievalDocument source = sources.get(index);
            int inputRank = index + 1;
            String candidateId = EvidenceCandidateIdentity.candidateId(source);
            if (inputRank <= cap) {
                included.add(source);
                decisions.add(new SourceRankingWindowDecision(
                    candidateId,
                    inputRank,
                    inputRank,
                    cap,
                    WindowDisposition.INCLUDED,
                    WindowReason.INCLUDED_IN_SOURCE_RANKING_WINDOW,
                    OrderingSource.FUSION_INPUT_ORDER
                ));
                candidates.add(new SourceRankingWindowCandidate(
                    candidateId,
                    EvidenceCandidateIdentity.lineageIdentity(source),
                    EvidenceIdentityResolver.citationIdentityValue(source),
                    EvidenceCandidateIdentity.identityResolutionStatus(source),
                    CandidateLane.SOURCE
                ));
            }
            else {
                decisions.add(new SourceRankingWindowDecision(
                    candidateId,
                    inputRank,
                    null,
                    cap,
                    WindowDisposition.FILTERED,
                    WindowReason.FILTERED_BY_SOURCE_RANKING_WINDOW_CAP,
                    OrderingSource.FUSION_INPUT_ORDER
                ));
            }
        }
        return new Result(included, decisions, candidates, false, "");
    }

    private Result buildFailure(List<RetrievalDocument> sources, int cap, RuntimeException exception) {
        List<SourceRankingWindowDecision> decisions = new ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            RetrievalDocument source = sources.get(index);
            if (source == null) {
                continue;
            }
            EvidenceCandidateIdentity.ensure(source);
            decisions.add(new SourceRankingWindowDecision(
                EvidenceCandidateIdentity.candidateId(source),
                index + 1,
                null,
                cap,
                WindowDisposition.FILTERED,
                WindowReason.FILTERED_BY_SOURCE_RANKING_WINDOW_BUILD_FAILURE,
                OrderingSource.FUSION_INPUT_ORDER
            ));
        }
        return new Result(List.of(), decisions, List.of(), true,
            exception == null ? "" : exception.getClass().getSimpleName());
    }

    public record Result(
        List<RetrievalDocument> includedDocuments,
        List<SourceRankingWindowDecision> decisions,
        List<SourceRankingWindowCandidate> candidates,
        boolean buildFailed,
        String failureDetail
    ) {

        public Result {
            includedDocuments = includedDocuments == null ? List.of() : List.copyOf(includedDocuments);
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            failureDetail = failureDetail == null ? "" : failureDetail;
        }
    }
}
