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
@TableName("smartledge_document_strategy_plan")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentStrategyPlan extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private Long documentId;

    private Integer planVersion;

    private Integer planSource;

    private Integer planStatus;

    private Integer strategyCount;

    private String strategySnapshot;

    private String chunkingContractJson;

    private String recommendReason;

    private String adjustNote;

    private Long confirmUserId;

    private Date confirmTime;
}
