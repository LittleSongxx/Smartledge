package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.model.DocumentRetrieveRequest;
import org.smartledge.ai.manage.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.manage.model.StructureAnchoredEvidenceRequest;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.Collection;
import java.util.List;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentKnowledgeService {

    List<KnowledgeDocumentDescriptor> listRetrievableDocuments();

    List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(Collection<Long> knowledgeBaseIds);

    List<RetrievalDocument> vectorSearch(DocumentRetrieveRequest request);

    List<RetrievalDocument> keywordSearch(DocumentRetrieveRequest request);

    List<RetrievalDocument> elevateToParentBlocks(List<RetrievalDocument> childDocuments, int maxChars);

    default List<RetrievalDocument> expandStructureAnchoredEvidence(StructureAnchoredEvidenceRequest request) {
        return List.of();
    }
}
