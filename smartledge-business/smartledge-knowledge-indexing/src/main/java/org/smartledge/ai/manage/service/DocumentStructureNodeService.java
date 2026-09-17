package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.data.SuperAgentDocumentStructureNode;
import org.smartledge.ai.manage.support.DocumentStructureNodeCandidate;

import java.util.List;
import java.util.Map;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentStructureNodeService {

    Long resolveCurrentParseTaskId(Long documentId);

    List<SuperAgentDocumentStructureNode> replaceDocumentNodes(Long documentId,
                                                               Long parseTaskId,
                                                               List<DocumentStructureNodeCandidate> candidates);

    List<SuperAgentDocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId);

    Map<Long, SuperAgentDocumentStructureNode> nodeMap(Long documentId, Long parseTaskId);

    default List<SuperAgentDocumentStructureNode> listChildren(Long documentId, Long parseTaskId, Long parentNodeId) {
        return List.of();
    }

    default SuperAgentDocumentStructureNode findById(Long documentId, Long parseTaskId, Long nodeId) {
        return null;
    }

    default SuperAgentDocumentStructureNode findPreviousSibling(Long documentId, Long parseTaskId, Long nodeId) {
        return null;
    }

    default SuperAgentDocumentStructureNode findNextSibling(Long documentId, Long parseTaskId, Long nodeId) {
        return null;
    }

    void deleteByDocumentId(Long documentId);
}
