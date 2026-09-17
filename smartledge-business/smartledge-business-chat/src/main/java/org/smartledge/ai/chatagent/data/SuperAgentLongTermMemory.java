package org.smartledge.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_long_term_memory")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentLongTermMemory extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    @TableField("dialogue_code")
    private String conversationId;

    private Long userId;

    @TableField("entity_key")
    private String entityKey;

    @TableField("fact_text")
    private String factText;

    @TableField("source_kind")
    private String sourceKind;

    private String lifecycle;

    @TableField("provenance_exchange_id")
    private Long provenanceExchangeId;

    private Integer version;
}
