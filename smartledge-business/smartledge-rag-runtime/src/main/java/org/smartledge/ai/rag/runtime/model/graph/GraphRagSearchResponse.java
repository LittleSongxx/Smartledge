package org.smartledge.ai.rag.runtime.model.graph;

import java.util.List;

/** Results and execution observation returned through the consumer-owned GraphRAG seam. */
public record GraphRagSearchResponse(
    List<GraphRagSearchResult> results,
    GraphRagSearchObservation observation
) {

    public GraphRagSearchResponse {
        results = results == null ? List.of() : List.copyOf(results);
        observation = observation == null
            ? GraphRagSearchObservation.withoutHints(List.of())
            : observation;
    }

    public static GraphRagSearchResponse withoutHints(List<GraphRagSearchResult> results) {
        return new GraphRagSearchResponse(results, GraphRagSearchObservation.withoutHints(List.of()));
    }
}
