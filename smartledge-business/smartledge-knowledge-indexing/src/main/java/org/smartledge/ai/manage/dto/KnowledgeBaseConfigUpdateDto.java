package org.smartledge.ai.manage.dto;

import lombok.Data;

@Data
public class KnowledgeBaseConfigUpdateDto {

    private String id;

    private String retrievalConfigJson;

    private String graphRagConfigJson;

    private String raptorConfigJson;

    private String metadataFilterJson;

    private String operatorId;
}
