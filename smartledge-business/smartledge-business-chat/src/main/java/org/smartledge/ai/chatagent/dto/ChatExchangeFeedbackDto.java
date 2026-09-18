package org.smartledge.ai.chatagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 轮次用户反馈提交：UP=有帮助，DOWN=没有帮助（DOWN 是金标候选的主要来源）。
 */
@Data
public class ChatExchangeFeedbackDto {

    @NotBlank(message = "conversationId 不能为空")
    private String conversationId;

    @NotNull(message = "exchangeId 不能为空")
    private Long exchangeId;

    @NotNull(message = "rating 不能为空")
    @Pattern(regexp = "UP|DOWN", message = "rating 只允许 UP 或 DOWN")
    private String rating;

    @Size(max = 500, message = "反馈说明最多 500 字")
    private String comment;
}
