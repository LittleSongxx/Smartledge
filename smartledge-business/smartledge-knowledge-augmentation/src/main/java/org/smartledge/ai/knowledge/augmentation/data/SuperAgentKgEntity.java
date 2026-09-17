package org.smartledge.ai.knowledge.augmentation.data;

import com.baomidou.mybatisplus.annotation.IdType;
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
@TableName("smartledge_kg_entity")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKgEntity extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long documentId;

    private Long taskId;

    private String entityKey;

    private String name;

    private String normalizedName;

    private String entityType;

    private String description;

    private String metadataJson;
}
