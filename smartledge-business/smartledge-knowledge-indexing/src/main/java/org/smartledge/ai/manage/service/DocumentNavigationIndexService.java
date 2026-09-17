package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.data.SuperAgentDocumentStructureNode;

import java.util.List;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentNavigationIndexService {

    void reindexDocumentNodes(Long documentId, Long parseTaskId, List<SuperAgentDocumentStructureNode> nodes);

    void deleteByDocumentId(Long documentId);

    List<NavigationSectionHit> searchSections(Long documentId,
                                              String topic,
                                              String facet,
                                              String informationNeed,
                                              String question,
                                              int size);

    record NavigationSectionHit(
        Long nodeId,
        String nodeCode,
        String title,
        String sectionPath,
        String canonicalPath,
        double score
    ) {
    }
}
