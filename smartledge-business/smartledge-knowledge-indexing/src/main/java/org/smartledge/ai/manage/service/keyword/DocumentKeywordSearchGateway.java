package org.smartledge.ai.manage.service.keyword;

import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.manage.model.DocumentRetrieveRequest;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.List;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentKeywordSearchGateway {

    void indexChunks(List<SuperAgentDocumentChunk> chunkList);

    void deleteByChunkIds(Long documentId, Long taskId, List<Long> chunkIds);

    List<RetrievalDocument> search(DocumentRetrieveRequest request);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);
}
