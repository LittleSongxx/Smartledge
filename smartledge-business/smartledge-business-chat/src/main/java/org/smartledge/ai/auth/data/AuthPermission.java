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
 * 权限字典（smartledge_permission）。
 *
 * <p>该表是全局字典，**没有 tenant_id**：权限编码 {@code resource:action} 由平台定义，
 * 租户差异体现在"角色拿到哪些权限"，而不是"权限本身分租户"。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_permission")
@EqualsAndHashCode(callSuper = true)
public class AuthPermission extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String permissionCode;

    private String permissionName;

    private String permissionGroup;

    private String description;
}
