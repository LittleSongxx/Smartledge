package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.manage.service.DocumentStructureNodeService;
import org.smartledge.ai.rag.runtime.model.DocumentStructureNode;
import org.smartledge.ai.rag.runtime.port.DocumentStructurePort;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentStructurePortAdapter implements DocumentStructurePort {

    private final DocumentStructureNodeService delegate;

    public DocumentStructurePortAdapter(DocumentStructureNodeService delegate) {
        this.delegate = delegate;
    }

    @Override public Long resolveCurrentParseTaskId(Long documentId) { return delegate.resolveCurrentParseTaskId(documentId); }
    @Override public List<DocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId) { return delegate.listDocumentNodes(documentId, parseTaskId).stream().map(DocumentStructurePortAdapter::node).toList(); }
    @Override public List<DocumentStructureNode> listChildren(Long documentId, Long parseTaskId, Long parentNodeId) { return delegate.listChildren(documentId, parseTaskId, parentNodeId).stream().map(DocumentStructurePortAdapter::node).toList(); }
    @Override public DocumentStructureNode findById(Long documentId, Long parseTaskId, Long nodeId) { return node(delegate.findById(documentId, parseTaskId, nodeId)); }
    @Override public DocumentStructureNode findPreviousSibling(Long documentId, Long parseTaskId, Long nodeId) { return node(delegate.findPreviousSibling(documentId, parseTaskId, nodeId)); }
    @Override public DocumentStructureNode findNextSibling(Long documentId, Long parseTaskId, Long nodeId) { return node(delegate.findNextSibling(documentId, parseTaskId, nodeId)); }

    private static DocumentStructureNode node(org.smartledge.ai.manage.data.SuperAgentDocumentStructureNode source) {
        if (source == null) return null;
        DocumentStructureNode target = new DocumentStructureNode();
        BeanUtils.copyProperties(source, target);
        return target;
    }
}
