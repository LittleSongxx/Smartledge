package org.smartledge.ai.knowledge.augmentation.port;

import org.smartledge.ai.knowledge.augmentation.config.GraphRagExecutionProperties;

/** Composition-root seam for instance GraphRAG execution resources. */
@FunctionalInterface
public interface GraphRagExecutionConfigurationPort {
    GraphRagExecutionProperties current();
}
