package org.smartledge.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

/**
 * @description: 数据实体
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_document_task_log")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentTaskLog extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private Long taskId;

    private Long documentId;

    private Integer stageType;

    private Integer eventType;

    private Integer logLevel;

    private Integer operatorType;

    private Long operatorId;

    private String content;

    private String detailJson;
}
