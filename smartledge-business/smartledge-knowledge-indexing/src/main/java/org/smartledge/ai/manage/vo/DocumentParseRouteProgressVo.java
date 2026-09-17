package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseRouteProgressVo {

    private Long documentId;

    private Long taskId;

    private Integer taskType;

    private String taskTypeName;

    private Integer taskStatus;

    private String taskStatusName;

    private Integer currentStage;

    private String currentStageName;

    private String stageCode;

    private String stageLabel;

    private Integer parseStatus;

    private String parseStatusName;

    private Integer strategyStatus;

    private String strategyStatusName;

    private String parseMode;

    private String parserName;

    private String parserVersion;

    private String jobId;

    private Integer pageCount;

    private Integer blockCount;

    private Integer tableCount;

    private Integer figureCount;

    private Integer captionCount;

    private Boolean planReady;

    private Long planId;

    private String recommendReason;

    private Integer parentStepCount;

    private Integer childStepCount;

    private Date startTime;

    private Date finishTime;

    private Long costMillis;

    private Long elapsedMillis;

    private Long latestLogId;

    private Long totalLogCount;

    private List<DocumentTaskLogVo> logs;

    private String errorCode;

    private String errorMsg;

    private Boolean running;
}
