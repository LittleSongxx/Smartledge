package org.smartledge.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

import java.util.Date;

/**
 * @description: 数据实体
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_document_task")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentTask extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long documentId;

    private Long planId;

    private Long sourceParseTaskId;

    private Integer taskType;

    private Integer taskStatus;

    private Integer currentStage;

    private Integer triggerSource;

    private String strategySnapshot;

    private Integer retryCount;

    private Date startTime;

    private Date finishTime;

    private Long costMillis;

    private String errorCode;

    private String errorMsg;

    private String extJson;
}
