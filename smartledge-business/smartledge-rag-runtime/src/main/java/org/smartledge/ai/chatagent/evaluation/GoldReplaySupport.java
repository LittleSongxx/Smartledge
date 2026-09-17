package org.smartledge.ai.chatagent.evaluation;

import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeResult;
import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeSubQuestion;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Projects probe source identities into the gold scorer without calling the engine. */
public final class GoldReplaySupport {

    private GoldReplaySupport() {
    }

    public static List<String> sourceIdentities(RetrievalProbeResult probe) {
        if (probe == null || probe.getSubQuestions() == null) {
            return List.of();
        }
        Set<String> identities = new LinkedHashSet<>();
        for (RetrievalProbeSubQuestion subQuestion : probe.getSubQuestions()) {
            if (subQuestion == null || subQuestion.getSourceCandidateIdentities() == null) {
                continue;
            }
            for (String identity : subQuestion.getSourceCandidateIdentities()) {
                if (identity != null && !identity.isBlank()) {
                    identities.add(identity);
                }
            }
        }
        return List.copyOf(identities);
    }
}
