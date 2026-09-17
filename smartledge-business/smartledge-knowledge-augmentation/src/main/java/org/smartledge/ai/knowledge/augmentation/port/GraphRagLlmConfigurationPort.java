package org.smartledge.ai.knowledge.augmentation.port;

import org.smartledge.ai.knowledge.augmentation.config.GraphRagExtractionOptions;

/**
 * Consumer-owned seam for dynamic GraphRAG LLM advisor toggles and limits.
 */
public interface GraphRagLlmConfigurationPort {

    default GraphRagExtractionOptions extraction() {
        throw new IllegalStateException("GraphRAG extraction configuration provider is not available");
    }

    default boolean communityReportEnabled() {
        throw new IllegalStateException("GraphRAG community-report configuration provider is not available");
    }

    default boolean entityResolutionEnabled() {
        throw new IllegalStateException("GraphRAG entity-resolution configuration provider is not available");
    }

    default boolean queryPlanEnabled() {
        throw new IllegalStateException("GraphRAG query-plan configuration provider is not available");
    }
}
