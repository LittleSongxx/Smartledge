package org.smartledge.ai.manage.dto;

import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/
@Data
public class TopicDocumentRelationSaveDto {

    private String knowledgeBaseId;

    private String topicId;

    private String documentId;

    private String relationScore;

    private String relationSource;

    private String reason;

    private String operatorId;
}
