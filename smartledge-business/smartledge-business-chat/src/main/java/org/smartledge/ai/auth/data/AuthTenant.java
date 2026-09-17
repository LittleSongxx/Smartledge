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
 * 租户（smartledge_tenant）。登录时按租户编码定位租户，再用 (tenant_id, username) 定位账号。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_tenant")
@EqualsAndHashCode(callSuper = true)
public class AuthTenant extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String tenantName;

    private String description;
}
