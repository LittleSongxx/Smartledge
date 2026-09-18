package org.smartledge.ai.knowledge.indexing.port;

import java.util.List;

/** One request per invocation; the caller owns batch/task retries. Results are fully validated and input ordered. */
public interface EmbeddingPort {
    List<float[]> embed(List<String> texts);
    default float[] embed(String text) { return embed(List.of(text)).get(0); }
    /** The same effective model used by both queries and index labels. */
    String model();

    /**
     * 该端口产出的向量维度，与 pgvector 列类型和 {@code app.ai.embedding.dimensions} 是同一契约。
     * 适配器无法声明时返回 {@code -1}，调用方此时跳过长度校验。
     */
    default int dimensions() { return -1; }
}
