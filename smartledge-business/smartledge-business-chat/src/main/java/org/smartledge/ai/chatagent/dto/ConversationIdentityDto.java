package org.smartledge.ai.chatagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/

@Data
public class ConversationIdentityDto {

    @NotBlank(message = "conversationId 不能为空")
    private String conversationId;
}
