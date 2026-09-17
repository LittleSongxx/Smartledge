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
 * 用户角色关联（smartledge_user_role）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_user_role")
@EqualsAndHashCode(callSuper = true)
public class AuthUserRole extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private Long userId;

    private Long roleId;
}
