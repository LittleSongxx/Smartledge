package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableDescriptor;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableQuery;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTableQueryPlan {

    private DocumentTableDescriptor table;

    private DocumentTableQuery query;

    private String reason;
}
