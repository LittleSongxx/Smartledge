package org.smartledge.ai.knowledge.augmentation.port;

/**
 * Consumer-owned seam for RAPTOR build controls that are not knowledge-base scoped.
 */
@FunctionalInterface
public interface RaptorBuildConfigurationPort {

    int embeddingBatchSize();

    default int llmConcurrency() {
        throw new IllegalStateException("RAPTOR build configuration provider is not available");
    }
}
