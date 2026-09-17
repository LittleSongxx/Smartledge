package org.smartledge.ai.chatagent.dto;

import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/

@Data
public class ConversationSessionListQueryDto {

    private String keyword;

    private String chatMode;

    private String turnStatus;

    private String pageNo;

    private String pageSize;
}
