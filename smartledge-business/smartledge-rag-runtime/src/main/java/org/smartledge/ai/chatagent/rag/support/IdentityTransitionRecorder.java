package org.smartledge.ai.chatagent.rag.support;

import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.CandidateEndpoint;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.IdentityTransition;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.IdentityTransitionStage;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.IdentityTransitionType;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures identity/cardinality transitions at the producer boundary where they occur. */
public final class IdentityTransitionRecorder {

    private IdentityTransitionRecorder() {
    }

    public static IdentityTransition capture(String transitionId,
                                             IdentityTransitionType type,
                                             IdentityTransitionStage stage,
                                             List<RetrievalDocument> inputs,
                                             List<RetrievalDocument> outputs) {
        List<CandidateEndpoint> from = endpoints(inputs);
        List<CandidateEndpoint> to = endpoints(outputs);
        if (sameCandidates(from, to)) {
            return null;
        }
        return new IdentityTransition(
            transitionId,
            from,
            to,
            type,
            stage,
            from.size(),
            to.size()
        );
    }

    private static List<CandidateEndpoint> endpoints(List<RetrievalDocument> candidates) {
        Map<String, CandidateEndpoint> endpoints = new LinkedHashMap<>();
        if (candidates != null) {
            for (RetrievalDocument candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                EvidenceCandidateIdentity.ensure(candidate);
                CandidateEndpoint endpoint = EvidenceCandidateIdentity.endpoint(candidate);
                endpoints.putIfAbsent(endpoint.candidateId(), endpoint);
            }
        }
        return List.copyOf(endpoints.values());
    }

    private static boolean sameCandidates(List<CandidateEndpoint> left, List<CandidateEndpoint> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            CandidateEndpoint leftCandidate = left.get(index);
            CandidateEndpoint rightCandidate = right.get(index);
            if (!leftCandidate.candidateId().equals(rightCandidate.candidateId())
                || !leftCandidate.lineageIdentity().equals(rightCandidate.lineageIdentity())) {
                return false;
            }
        }
        return true;
    }
}
