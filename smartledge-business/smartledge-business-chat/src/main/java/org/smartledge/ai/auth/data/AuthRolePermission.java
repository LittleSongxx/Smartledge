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
 * 角色权限关联（smartledge_role_permission）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_role_permission")
@EqualsAndHashCode(callSuper = true)
public class AuthRolePermission extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private Long roleId;

    private Long permissionId;
}
