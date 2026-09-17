package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.manage.service.DocumentStructureGraphService;
import org.smartledge.ai.rag.runtime.model.graph.GraphItem;
import org.smartledge.ai.rag.runtime.model.graph.GraphSection;
import org.smartledge.ai.rag.runtime.port.DocumentStructureGraphPort;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentStructureGraphPortAdapter implements DocumentStructureGraphPort {

    private final DocumentStructureGraphService delegate;

    public DocumentStructureGraphPortAdapter(DocumentStructureGraphService delegate) {
        this.delegate = delegate;
    }

    @Override public boolean isGraphAvailable(Long documentId) { return delegate.isGraphAvailable(documentId); }
    @Override public GraphSection findSectionById(Long documentId, Long sectionNodeId) { return section(delegate.findSectionById(documentId, sectionNodeId)); }
    @Override public GraphSection findSectionByCode(Long documentId, String nodeCode) { return section(delegate.findSectionByCode(documentId, nodeCode)); }
    @Override public GraphSection findSectionByTitle(Long documentId, String title) { return section(delegate.findSectionByTitle(documentId, title)); }
    @Override public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) { return section(delegate.findSectionByCanonicalPath(documentId, canonicalPath)); }
    @Override public List<GraphSection> listSections(Long documentId) { return delegate.listSections(documentId).stream().map(DocumentStructureGraphPortAdapter::section).toList(); }
    @Override public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) { return delegate.listChildren(documentId, sectionNodeId).stream().map(DocumentStructureGraphPortAdapter::section).toList(); }
    @Override public GraphSection parentSection(Long documentId, Long sectionNodeId) { return section(delegate.parentSection(documentId, sectionNodeId)); }
    @Override public GraphSection previousSibling(Long documentId, Long sectionNodeId) { return section(delegate.previousSibling(documentId, sectionNodeId)); }
    @Override public GraphSection nextSibling(Long documentId, Long sectionNodeId) { return section(delegate.nextSibling(documentId, sectionNodeId)); }
    @Override public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) { return item(delegate.findItemByIndex(documentId, sectionNodeId, itemIndex)); }
    @Override public List<GraphItem> listItems(Long documentId, Long sectionNodeId) { return delegate.listItems(documentId, sectionNodeId).stream().map(DocumentStructureGraphPortAdapter::item).toList(); }

    private static GraphSection section(org.smartledge.ai.manage.model.graph.GraphSection source) {
        if (source == null) return null;
        GraphSection target = new GraphSection();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private static GraphItem item(org.smartledge.ai.manage.model.graph.GraphItem source) {
        if (source == null) return null;
        GraphItem target = new GraphItem();
        BeanUtils.copyProperties(source, target);
        return target;
    }
}
