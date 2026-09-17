package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.DocumentStructureNode;

import java.util.List;

public interface DocumentStructurePort {

    Long resolveCurrentParseTaskId(Long documentId);

    List<DocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId);

    List<DocumentStructureNode> listChildren(Long documentId, Long parseTaskId, Long parentNodeId);

    DocumentStructureNode findById(Long documentId, Long parseTaskId, Long nodeId);

    DocumentStructureNode findPreviousSibling(Long documentId, Long parseTaskId, Long nodeId);

    DocumentStructureNode findNextSibling(Long documentId, Long parseTaskId, Long nodeId);
}
