package org.smartledge.ai.manage.support;

import org.smartledge.ai.knowledge.indexing.port.DocumentVectorConfigurationPort;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.springframework.stereotype.Component;

/** Composition-root adapter for the indexing vectorization settings. */
@Component
public final class DocumentManageVectorConfigurationAdapter implements DocumentVectorConfigurationPort {

    private final DocumentManageProperties properties;

    public DocumentManageVectorConfigurationAdapter(DocumentManageProperties properties) {
        this.properties = properties == null ? new DocumentManageProperties() : properties;
    }

    @Override
    public EmbeddingDefaults currentEmbeddingDefaults() {
        DocumentManageProperties.IndexBuild indexBuild = properties.getIndexBuild();
        if (indexBuild == null) {
            return EmbeddingDefaults.defaults();
        }
        return new EmbeddingDefaults(
            indexBuild.getEmbeddingBatchSize(),
            indexBuild.getEmbeddingParallelism(),
            indexBuild.getEmbeddingBatchMaxAttempts(),
            indexBuild.getEmbeddingBatchRetryBackoffMillis()
        );
    }
}
