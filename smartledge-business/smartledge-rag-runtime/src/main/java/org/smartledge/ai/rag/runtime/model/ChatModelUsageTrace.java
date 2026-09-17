package org.smartledge.ai.rag.runtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 单次模型调用的使用量轨迹
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatModelUsageTrace {

    private String stageName;

    private String provider;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Double estimatedCost;

    private Long durationMs;

    private String status;

    private String responseId;

    private String finishReason;

    /** PROVIDER, ESTIMATED, or MIXED; null for the streaming implementation pending ticket 02. */
    private String usageSource;

    private Boolean promptTokensEstimated;

    private Boolean completionTokensEstimated;

    private Boolean totalTokensEstimated;
}
