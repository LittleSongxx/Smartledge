package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.graph.GraphItem;
import org.smartledge.ai.rag.runtime.model.graph.GraphSection;

import java.util.List;

public interface DocumentStructureGraphPort {

    default boolean isGraphAvailable(Long documentId) {
        return documentId != null;
    }

    GraphSection findSectionById(Long documentId, Long sectionNodeId);

    GraphSection findSectionByCode(Long documentId, String nodeCode);

    GraphSection findSectionByTitle(Long documentId, String title);

    GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath);

    List<GraphSection> listSections(Long documentId);

    List<GraphSection> listChildren(Long documentId, Long sectionNodeId);

    GraphSection parentSection(Long documentId, Long sectionNodeId);

    GraphSection previousSibling(Long documentId, Long sectionNodeId);

    GraphSection nextSibling(Long documentId, Long sectionNodeId);

    GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex);

    List<GraphItem> listItems(Long documentId, Long sectionNodeId);
}
