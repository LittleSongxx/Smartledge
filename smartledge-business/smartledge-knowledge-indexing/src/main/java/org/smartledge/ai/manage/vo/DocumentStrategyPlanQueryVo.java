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
public class DocumentStrategyPlanQueryVo {

    private Long documentId;

    private String documentName;

    private Integer parseStatus;

    private String parseStatusName;

    private Integer strategyStatus;

    private String strategyStatusName;

    private Integer indexStatus;

    private String indexStatusName;

    private String parseErrorMsg;

    private Boolean planReady;

    private DocumentStrategyPlanVo plan;
}
