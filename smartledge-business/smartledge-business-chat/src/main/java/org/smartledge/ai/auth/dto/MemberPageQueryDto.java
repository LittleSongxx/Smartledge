package org.smartledge.ai.auth.dto;

import lombok.Data;

/**
 * 成员列表查询（S23-B2）。
 *
 * <p>字段与既有管理端 DTO 保持同一形状：数值 id 与页码以字符串传输，避免 JS 侧的大整数精度损失。</p>
 */
@Data
public class TenantMemberPageQueryDto {

    private String pageNo;

    private String pageSize;

    /** 按登录名或显示名模糊匹配；空表示不过滤。 */
    private String keyword;
}
