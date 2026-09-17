package org.smartledge.ai.knowledge.augmentation.port;

import org.smartledge.ai.knowledge.augmentation.config.GraphRagDiagnosticProperties;

/** Composition-root seam for GraphRAG diagnostic retention limits. */
@FunctionalInterface
public interface GraphRagDiagnosticConfigurationPort {
    GraphRagDiagnosticProperties current();
}
