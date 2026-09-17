package org.smartledge.ai.rag.runtime.model;

/** Immutable configuration payload exposed by the manage adapter to runtime resolution. */
public record KnowledgeBaseRuntimeConfigSource(
    Long id,
    String name,
    String retrievalConfigJson,
    String graphRagConfigJson,
    String raptorConfigJson
) {
}
