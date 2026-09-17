package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;

import java.util.List;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentVectorGateway {

    void vectorize(List<SuperAgentDocumentChunk> chunkList);

    void deleteByChunkIds(Long documentId, Long taskId, List<Long> chunkIds);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);
}
