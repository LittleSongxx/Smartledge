package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.DocumentRetrieveRequest;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.rag.runtime.model.StructureAnchoredEvidenceRequest;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.Collection;
import java.util.List;

public interface DocumentEvidencePort {

    List<KnowledgeDocumentDescriptor> listRetrievableDocuments();

    List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(Collection<Long> knowledgeBaseIds);

    List<RetrievalDocument> vectorSearch(DocumentRetrieveRequest request);

    List<RetrievalDocument> keywordSearch(DocumentRetrieveRequest request);

    List<RetrievalDocument> elevateToParentBlocks(List<RetrievalDocument> childDocuments, int maxChars);

    default List<RetrievalDocument> expandStructureAnchoredEvidence(StructureAnchoredEvidenceRequest request) {
        return List.of();
    }
}
