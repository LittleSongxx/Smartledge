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
public class KnowledgeTopicItemVo {

    private String id;

    private String knowledgeBaseId;

    private String topicName;

    private String scopeId;

    private String description;

    private String aliases;

    private String examples;

    private String answerShape;

    private String executionPreference;

    private String sortOrder;
}
