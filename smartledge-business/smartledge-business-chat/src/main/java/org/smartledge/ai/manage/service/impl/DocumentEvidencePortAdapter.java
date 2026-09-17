package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.manage.service.DocumentKnowledgeService;
import org.smartledge.ai.rag.runtime.model.DocumentRetrieveFilters;
import org.smartledge.ai.rag.runtime.model.DocumentRetrieveRequest;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.rag.runtime.model.StructureAnchoredEvidenceRequest;
import org.smartledge.ai.rag.runtime.port.DocumentEvidencePort;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
public class DocumentEvidencePortAdapter implements DocumentEvidencePort {

    private final DocumentKnowledgeService delegate;

    public DocumentEvidencePortAdapter(DocumentKnowledgeService delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
        return delegate.listRetrievableDocuments().stream().map(DocumentEvidencePortAdapter::descriptor).toList();
    }

    @Override
    public List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(Collection<Long> knowledgeBaseIds) {
        return delegate.listRetrievableDocumentsByKnowledgeBaseIds(knowledgeBaseIds).stream()
            .map(DocumentEvidencePortAdapter::descriptor)
            .toList();
    }

    @Override
    public List<RetrievalDocument> vectorSearch(DocumentRetrieveRequest request) {
        return delegate.vectorSearch(request(request));
    }

    @Override
    public List<RetrievalDocument> keywordSearch(DocumentRetrieveRequest request) {
        return delegate.keywordSearch(request(request));
    }

    @Override
    public List<RetrievalDocument> elevateToParentBlocks(List<RetrievalDocument> childDocuments, int maxChars) {
        return delegate.elevateToParentBlocks(childDocuments, maxChars);
    }

    @Override
    public List<RetrievalDocument> expandStructureAnchoredEvidence(StructureAnchoredEvidenceRequest request) {
        return delegate.expandStructureAnchoredEvidence(structureRequest(request));
    }

    private static KnowledgeDocumentDescriptor descriptor(org.smartledge.ai.manage.model.KnowledgeDocumentDescriptor source) {
        KnowledgeDocumentDescriptor target = new KnowledgeDocumentDescriptor();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private static org.smartledge.ai.manage.model.DocumentRetrieveRequest request(DocumentRetrieveRequest source) {
        org.smartledge.ai.manage.model.DocumentRetrieveRequest target = new org.smartledge.ai.manage.model.DocumentRetrieveRequest();
        BeanUtils.copyProperties(source, target, "filters");
        target.setFilters(filters(source.getFilters()));
        return target;
    }

    private static org.smartledge.ai.manage.model.DocumentRetrieveFilters filters(DocumentRetrieveFilters source) {
        if (source == null) {
            return null;
        }
        org.smartledge.ai.manage.model.DocumentRetrieveFilters target = new org.smartledge.ai.manage.model.DocumentRetrieveFilters();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private static org.smartledge.ai.manage.model.StructureAnchoredEvidenceRequest structureRequest(StructureAnchoredEvidenceRequest source) {
        return org.smartledge.ai.manage.model.StructureAnchoredEvidenceRequest.builder()
            .candidateDocuments(source.getCandidateDocuments())
            .sectionAnchors(source.getSectionAnchors())
            .structureNodeIds(source.getStructureNodeIds())
            .sourceHeadingNodeIds(source.getSourceHeadingNodeIds())
            .canonicalPaths(source.getCanonicalPaths())
            .documentIds(source.getDocumentIds())
            .taskIds(source.getTaskIds())
            .knowledgeBaseIds(source.getKnowledgeBaseIds())
            .maxPerAnchor(source.getMaxPerAnchor())
            .maxTotal(source.getMaxTotal())
            .maxChars(source.getMaxChars())
            .build();
    }
}
