package org.smartledge.ai.knowledge.indexing.port;

import java.util.List;

/** One request per invocation; the caller owns batch/task retries. Results are fully validated and input ordered. */
public interface EmbeddingPort {
    List<float[]> embed(List<String> texts);
    default float[] embed(String text) { return embed(List.of(text)).get(0); }
    /** The same effective model used by both queries and index labels. */
    String model();
}
