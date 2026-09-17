package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.knowledge.augmentation.model.raptor.RaptorBuildResult;

import java.util.List;

public interface RaptorBuildService {

    RaptorBuildResult rebuildDocumentTree(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks);

    RaptorBuildResult rebuildKnowledgeScopeTree(Long knowledgeBaseId, Long scopeId);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);

    void deleteByScope(String scopeType, String scopeKey);
}
