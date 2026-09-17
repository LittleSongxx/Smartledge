package org.smartledge.ai.manage.dto;

import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/
@Data
public class KnowledgeRouteTraceQueryDto {

    private String conversationId;

    private String mode;

    private String routeStatus;

    private String pageNo;

    private String pageSize;
}
