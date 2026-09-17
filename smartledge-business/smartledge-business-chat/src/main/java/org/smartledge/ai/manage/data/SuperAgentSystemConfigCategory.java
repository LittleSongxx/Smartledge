package org.smartledge.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.smartledge.database.data.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("smartledge_system_config_category")
public class SuperAgentSystemConfigCategory extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String groupKey;
    private String categoryKey;
    private String categoryLabel;
    private String categoryDescription;
    private Integer sortOrder;
}
