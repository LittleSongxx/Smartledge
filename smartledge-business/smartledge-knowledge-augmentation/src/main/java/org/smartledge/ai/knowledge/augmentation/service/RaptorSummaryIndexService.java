package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.knowledge.augmentation.data.SuperAgentRaptorNode;

import java.util.List;

public interface RaptorSummaryIndexService {

    void indexNodes(List<SuperAgentRaptorNode> nodes);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);

    void deleteByScope(String scopeType, String scopeKey);

    List<RaptorSummaryHit> search(String question, List<Long> documentIds, List<Long> taskIds, List<String> datasetScopeKeys, int topK);

    record RaptorSummaryHit(Long nodeId, double score) {
    }
}
