package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.table.DocumentTableDescriptor;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableQuery;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableQueryResult;

import java.util.List;

public interface DocumentTablePort {

    List<DocumentTableDescriptor> listTables(List<Long> documentIds, List<Long> indexTaskIds);

    DocumentTableQueryResult query(DocumentTableQuery query);
}
