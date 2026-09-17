package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 视图对象
 * @author: Song
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopicDocumentRelationItemVo {

    private String knowledgeBaseId;

    private String topicId;

    private String topicName;

    private String scopeId;

    private String scopeName;

    private String documentId;

    private String documentName;

    private String relationScore;

    private String relationSource;

    private String reason;
}
