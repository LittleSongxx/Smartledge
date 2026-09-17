package org.smartledge.ai.knowledge.indexing.port;

/**
 * Consumer-owned seam for the configuration needed by document indexing.
 *
 * <p>The indexing module only needs chunk defaults.  The provider may source
 * them from Spring properties, a database snapshot, or a test fixture without
 * exposing any of those implementations to this module.</p>
 */
@FunctionalInterface
public interface IndexingConfigurationPort {

    ChunkDefaults currentChunkDefaults();

    record ChunkDefaults(Integer childRecursiveMaxChars,
                         Integer childRecursiveOverlapChars,
                         Integer childSemanticMaxChars,
                         Integer childSemanticMinChars,
                         Double childSemanticSimilarityThreshold,
                         Integer parentBlockMaxChars,
                         Integer parentBlockOverlapChars,
                         Integer parentSemanticMaxChars,
                         Integer parentSemanticMinChars) {

        public static ChunkDefaults defaults() {
            return new ChunkDefaults(
                800,
                120,
                700,
                240,
                0.18D,
                2200,
                180,
                1600,
                480
            );
        }
    }
}
