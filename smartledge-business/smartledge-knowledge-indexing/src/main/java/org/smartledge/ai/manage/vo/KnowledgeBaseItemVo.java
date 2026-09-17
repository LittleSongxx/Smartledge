package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseItemVo {

    private String id;

    private String baseName;

    private String description;

    private String retrievalConfigJson;

    private String graphRagConfigJson;

    private String raptorConfigJson;

    private String metadataFilterJson;

    private String isDefault;

    private String sortOrder;

    private String documentCount;

    private String retrievableDocumentCount;
}
