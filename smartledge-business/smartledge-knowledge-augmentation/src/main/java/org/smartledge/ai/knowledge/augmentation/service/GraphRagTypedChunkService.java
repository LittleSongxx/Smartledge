package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;

import java.util.List;

public interface GraphRagTypedChunkService {

    List<SuperAgentDocumentChunk> replaceTypedIndex(Long documentId,
                                                    Long taskId,
                                                    Long planId,
                                                    List<SuperAgentDocumentChunk> sourceChunks,
                                                    int startChunkNo);
}
