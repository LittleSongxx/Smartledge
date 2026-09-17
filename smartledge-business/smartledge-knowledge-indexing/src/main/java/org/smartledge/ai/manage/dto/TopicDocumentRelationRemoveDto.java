package org.smartledge.ai.manage.dto;

import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/
@Data
public class TopicDocumentRelationRemoveDto {

    private String knowledgeBaseId;

    private String topicId;

    private String documentId;

    private String operatorId;
}
