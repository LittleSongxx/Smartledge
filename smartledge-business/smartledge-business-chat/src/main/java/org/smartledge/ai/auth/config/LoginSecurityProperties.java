package org.smartledge.ai.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录安全策略（{@code app.auth.*}）。
 *
 * <p>这些是**应用策略**而不是系统参数，因此走配置文件而不是 {@code smartledge_system_config}：
 * 锁定阈值属于安全基线，不应被后台参数页随手改小。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class LoginSecurityProperties {

    /** 连续失败多少次后锁定账号。 */
    private int maxFailedAttempts = 5;

    /** 锁定时长（分钟）。 */
    private long lockMinutes = 15;

    /**
     * 未指定租户编码时使用的租户。
     *
     * <p>账号在租户内唯一（{@code uk_user_tenant_username}），因此登录必须知道是哪个租户；
     * 这里只给出"本地默认租户"的编码，真正的解析仍然走 {@code smartledge_tenant} 表，
     * 不存在硬编码租户 id。</p>
     */
    private String defaultTenantCode = "default";
}
