package org.smartledge.ai.knowledge.augmentation.port;

import org.smartledge.ai.knowledge.augmentation.config.GraphRagBuildProperties;

/**
 * Consumer-owned configuration seam for GraphRAG build controls.
 */
@FunctionalInterface
public interface GraphRagBuildConfigurationPort {

    GraphRagBuildProperties current();
}
