package org.smartledge.ai.chatagent.model.debug;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 单轮对话的调用限制统计
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatLimitStats {

    private Integer modelCallsUsed;

    private Integer modelCallsRunLimit;

    private Integer modelCallsThreadLimit;

    private Integer toolCallsUsed;

    private Integer toolCallsRunLimit;

    private Integer toolCallsThreadLimit;

    private boolean limitTriggered;

    private String limitReason;
}
