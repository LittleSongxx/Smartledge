package org.smartledge.ai.chatagent.rag.model;

import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.List;

/**
 * Final selection 前的物理候选边界。Source 候选可进入 evidence budget；Context 候选只能进入 Prompt background。
 */
public record EvidenceCandidatePools(List<RetrievalDocument> sourceDocuments, List<RetrievalDocument> contextDocuments) {

    public EvidenceCandidatePools {
        sourceDocuments = sourceDocuments == null ? List.of() : List.copyOf(sourceDocuments);
        contextDocuments = contextDocuments == null ? List.of() : List.copyOf(contextDocuments);
    }
}
