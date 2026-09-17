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

    /** 先墓碑：检索排除 status≠1，不立刻硬删 HNSW 点。 */
    default void tombstoneByDocumentId(Long documentId) {
        deleteByDocumentId(documentId);
    }

    default void tombstoneByTask(Long documentId, Long taskId) {
        deleteByTask(documentId, taskId);
    }

    default void tombstoneStaleTasks(Long documentId, Long currentTaskId) {
        // 默认不动作：权威缺失时不得乱删。覆盖实现按 currentTaskId 把其余行标 status=0。
    }
}
