package org.smartledge.ai.manage.dto;

import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/

@Data
public class DocumentUploadDto {

    private String documentName;

    private String operatorId;

    private String knowledgeBaseId;

    /** Optional document-level user metadata JSON object. */
    private String metadataJson;
}
