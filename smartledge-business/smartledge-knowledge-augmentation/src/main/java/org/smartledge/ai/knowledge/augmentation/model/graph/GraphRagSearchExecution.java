package org.smartledge.ai.knowledge.augmentation.model.graph;

import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchObservation;

import java.util.List;

/** Internal GraphRAG search result before the runtime adapter maps candidate types. */
public record GraphRagSearchExecution(
    List<GraphRagSearchResult> results,
    GraphRagSearchObservation observation
) {

    public GraphRagSearchExecution {
        results = results == null ? List.of() : List.copyOf(results);
        observation = observation == null
            ? GraphRagSearchObservation.withoutHints(List.of())
            : observation;
    }
}
