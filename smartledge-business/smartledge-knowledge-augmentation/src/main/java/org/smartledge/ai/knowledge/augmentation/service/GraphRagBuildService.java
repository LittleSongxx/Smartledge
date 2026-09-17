package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagBuildResult;

import java.util.List;

public interface GraphRagBuildService {

    GraphRagBuildResult rebuildDocumentGraph(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);
}
