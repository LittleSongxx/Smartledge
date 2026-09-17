package org.smartledge.ai.ragtools.port;

import org.smartledge.ai.ragtools.config.RagToolsProperties;

/** Composition-root seam for the Python rag-tools transport configuration. */
@FunctionalInterface
public interface RagToolsConfigurationPort {
    RagToolsProperties current();
}
