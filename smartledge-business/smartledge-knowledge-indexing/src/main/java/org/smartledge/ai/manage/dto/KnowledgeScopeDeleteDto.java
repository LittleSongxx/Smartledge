package org.smartledge.ai.manage.dto;

import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/
@Data
public class KnowledgeScopeDeleteDto {

    private String id;

    private String knowledgeBaseId;

    private String operatorId;
}
