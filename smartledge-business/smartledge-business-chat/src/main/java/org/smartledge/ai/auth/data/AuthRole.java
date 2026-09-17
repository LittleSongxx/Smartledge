package org.smartledge.ai.auth.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

/**
 * 角色（smartledge_role）。角色编码在租户内唯一，权限通过 role_permission 关联。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_role")
@EqualsAndHashCode(callSuper = true)
public class AuthRole extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private String roleCode;

    private String roleName;

    private String description;

    private Integer builtIn;
}
