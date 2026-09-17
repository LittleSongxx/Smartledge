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
public class DocumentStrategyPlanVo {

    private Long planId;

    private Integer planVersion;

    private Integer planSource;

    private String planSourceName;

    private Integer planStatus;

    private String planStatusName;

    private String strategySnapshot;

    private String recommendReason;

    private DocumentStrategyPipelineVo parentPipeline;

    private DocumentStrategyPipelineVo childPipeline;

    private org.smartledge.ai.manage.support.ChunkingContract chunkingContract;
}
