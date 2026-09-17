package org.smartledge.ai.rag.runtime.port;

import java.util.List;
import java.util.Map;

/** Consumer-owned rerank seam. Transport DTOs and HTTP failure details stay behind its adapter. */
@FunctionalInterface
public interface RerankPort {

    Response rerank(Request request);

    record Request(String query, List<Candidate> candidates, int topK) {
    }

    record Candidate(String id, String text, Map<String, Object> metadata) {
    }

    record Response(String model, List<Result> results) {
    }

    record Result(String id, Double score, Integer rank) {
    }
}
