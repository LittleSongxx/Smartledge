package org.smartledge.ai.manage.support;

import org.smartledge.ai.knowledge.indexing.port.IndexingConfigurationPort;
import org.smartledge.ai.manage.config.DocumentManageProperties;

/**
 * Composition-root adapter from the legacy management properties to the
 * indexing module's consumer-owned configuration seam.
 */
public final class DocumentManageIndexingConfigurationAdapter implements IndexingConfigurationPort {

    private final DocumentManageProperties properties;

    public DocumentManageIndexingConfigurationAdapter(DocumentManageProperties properties) {
        this.properties = properties == null ? new DocumentManageProperties() : properties;
    }

    @Override
    public ChunkDefaults currentChunkDefaults() {
        DocumentManageProperties.Chunk chunk = properties.getChunk();
        if (chunk == null) {
            return ChunkDefaults.defaults();
        }
        return new ChunkDefaults(
            chunk.getRecursiveMaxChars(),
            chunk.getRecursiveOverlapChars(),
            chunk.getSemanticMaxChars(),
            chunk.getSemanticMinChars(),
            chunk.getSemanticSimilarityThreshold(),
            chunk.getParentBlockMaxChars(),
            chunk.getParentBlockOverlapChars(),
            chunk.getParentSemanticMaxChars(),
            chunk.getParentSemanticMinChars()
        );
    }
}
