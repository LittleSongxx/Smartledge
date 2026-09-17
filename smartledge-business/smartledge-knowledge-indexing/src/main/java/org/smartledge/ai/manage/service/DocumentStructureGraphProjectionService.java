package org.smartledge.ai.manage.service;

import java.util.List;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentStructureGraphProjectionService {

    boolean enabled();

    void projectToGraph(Long documentId, Long parseTaskId);

    void deleteByDocumentId(Long documentId);

    default List<String> statusNotes() {
        return List.of();
    }
}
