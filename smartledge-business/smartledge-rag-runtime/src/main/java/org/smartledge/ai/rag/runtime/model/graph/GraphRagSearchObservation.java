package org.smartledge.ai.rag.runtime.model.graph;

import java.util.List;

/** Observable facts produced by one GraphRAG query execution. */
public record GraphRagSearchObservation(
    boolean entityHintsArrived,
    List<String> receivedEntityHints,
    List<String> usedEntitySeeds,
    boolean fallbackOccurred,
    FallbackReason fallbackReason,
    List<ResultSource> resultSources
) {

    public GraphRagSearchObservation {
        receivedEntityHints = immutableList(receivedEntityHints);
        usedEntitySeeds = immutableList(usedEntitySeeds);
        fallbackReason = fallbackReason == null ? FallbackReason.NONE : fallbackReason;
        resultSources = immutableList(resultSources);
    }

    public static GraphRagSearchObservation withoutHints(List<ResultSource> resultSources) {
        return new GraphRagSearchObservation(
            false,
            List.of(),
            List.of(),
            true,
            FallbackReason.EMPTY_HINTS,
            resultSources
        );
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public enum FallbackReason {
        NONE,
        EMPTY_HINTS,
        INVALID_HINTS,
        ENTITY_NOT_FOUND,
        ADVISOR_FAILURE
    }

    public enum ResultSource {
        ENTITY_EVIDENCE,
        RELATION_EVIDENCE,
        COMMUNITY_REPORT
    }
}
