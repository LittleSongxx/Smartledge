package org.smartledge.ai.auth.data;

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
 * 用户账号（smartledge_user）。
 *
 * <p>口令只以 BCrypt 哈希存在，明文不落库、不进日志。失败计数与锁定截止时间也在本表，
 * 因此"账号是否存在"和"账号是否被锁定"是同一行数据，登录失败处理不需要额外状态存储。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_user")
@EqualsAndHashCode(callSuper = true)
public class AuthUserAccount extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private String username;

    private String passwordHash;

    private String displayName;

    private String email;

    private Integer failedAttempts;

    private Date lockedUntil;

    private Date lastLoginAt;

    /**
     * 令牌版本。停用、改角色、登出后递增，使已签发 JWT 立即失效。
     */
    private Long tokenVersion;
}
