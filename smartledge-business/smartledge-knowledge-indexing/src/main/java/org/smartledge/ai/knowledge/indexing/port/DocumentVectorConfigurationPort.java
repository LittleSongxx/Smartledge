package org.smartledge.ai.knowledge.indexing.port;

/**
 * Consumer-owned configuration seam for document vectorization.
 *
 * <p>The indexing implementation only needs the embedding batch controls.
 * Property binding and database-backed overrides remain in the composition
 * root adapter.</p>
 */
@FunctionalInterface
public interface DocumentVectorConfigurationPort {

    EmbeddingDefaults currentEmbeddingDefaults();

    record EmbeddingDefaults(Integer batchSize,
                             Integer parallelism,
                             Integer maxAttempts,
                             Long retryBackoffMillis) {

        public static EmbeddingDefaults defaults() {
            return new EmbeddingDefaults(5, 1, 3, 1200L);
        }
    }
}
