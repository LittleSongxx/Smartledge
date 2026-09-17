package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.data.SuperAgentDocumentBlock;
import org.smartledge.ai.manage.model.table.DocumentTableDescriptor;
import org.smartledge.ai.manage.model.table.DocumentTableQuery;
import org.smartledge.ai.manage.model.table.DocumentTableQueryResult;
import org.smartledge.ai.manage.support.DocumentTableCandidate;

import java.util.List;

public interface DocumentTableStructureService {

    void replaceTaskTables(Long documentId,
                           Long taskId,
                           List<SuperAgentDocumentBlock> blockList,
                           List<DocumentTableCandidate> tableCandidates);

    /**
     * Resolves the frozen index-task scope to its immutable source parse tasks and lists tables
     * owned by those parse revisions. The two input lists are positionally paired.
     */
    List<DocumentTableDescriptor> listTables(List<Long> documentIds, List<Long> indexTaskIds);

    DocumentTableQueryResult query(DocumentTableQuery query);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);
}
