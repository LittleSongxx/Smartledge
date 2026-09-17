package org.smartledge.ai.rag.runtime.model.graph;

import java.util.List;

/**
 * Consumer-owned request for one GraphRAG search execution.
 *
 * <p>The document and task lists are the already-authorized hard scope. Entity hints are
 * advisory query seeds only; they must not be projected to a storage hard filter.</p>
 */
public record GraphRagSearchRequest(
    String originalQuestion,
    String executionQuery,
    List<Long> documentScope,
    List<Long> taskScope,
    int topK,
    int maxHops,
    List<String> entityHints
) {

    public GraphRagSearchRequest {
        documentScope = immutableList(documentScope);
        taskScope = immutableList(taskScope);
        entityHints = immutableList(entityHints);
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
