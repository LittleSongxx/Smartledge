package org.smartledge.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @description: 支撑组件
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyPlanDraft {

    private String strategySnapshot;

    private String recommendReason;

    private List<DocumentStrategyStepDraft> parentSteps;

    private List<DocumentStrategyStepDraft> childSteps;

    private String chunkingContractJson;

    public DocumentStrategyPlanDraft(String strategySnapshot, String recommendReason,
                                     List<DocumentStrategyStepDraft> parentSteps,
                                     List<DocumentStrategyStepDraft> childSteps) {
        this(strategySnapshot, recommendReason, parentSteps, childSteps, null);
    }
}
