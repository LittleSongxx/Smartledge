package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @description: 视图对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyPipelineVo {

    private String pipelineType;

    private String pipelineTypeName;

    private String strategySnapshot;

    private List<DocumentStrategyStepVo> steps;
}
