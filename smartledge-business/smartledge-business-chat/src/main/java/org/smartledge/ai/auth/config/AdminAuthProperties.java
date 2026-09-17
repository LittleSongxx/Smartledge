package org.smartledge.ai.auth.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录令牌配置。
 *
 * <p>签名密钥只来自本地/环境变量（{@code app.admin-auth.token-secret} /
 * {@code SMARTLEDGE_ADMIN_TOKEN_SECRET}），不能被持 {@code config:write} 的租户管理员
 * 经系统参数页改掉。有效期仍可走系统参数，因为它不是签名材料。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.admin-auth")
public class AdminAuthProperties {

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private ObjectProvider<SystemConfigProvider> systemConfigProvider;

    /**
     * JWT 签名密钥。只从本地字段读取，绝不回落到系统参数快照。
     */
    private String tokenSecret;

    /**
     * 本地默认有效期（分钟）；若系统参数已配置则以后者为准。
     */
    private Long tokenExpireMinutes = 720L;

    public String getTokenSecret() {
        return required(tokenSecret, "app.admin-auth.token-secret / SMARTLEDGE_ADMIN_TOKEN_SECRET");
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public Long getTokenExpireMinutes() {
        var snapshot = provider();
        if (snapshot != null && snapshot.getAdminAuth() != null
            && snapshot.getAdminAuth().getTokenExpireMinutes() >= 1) {
            return snapshot.getAdminAuth().getTokenExpireMinutes();
        }
        return tokenExpireMinutes == null || tokenExpireMinutes < 1 ? 720L : tokenExpireMinutes;
    }

    public void setTokenExpireMinutes(Long tokenExpireMinutes) {
        this.tokenExpireMinutes = tokenExpireMinutes;
    }

    /** 测试装配：注入系统参数提供者，只影响有效期，不影响签名密钥。 */
    public void bindSystemConfigProvider(ObjectProvider<SystemConfigProvider> systemConfigProvider) {
        this.systemConfigProvider = systemConfigProvider;
    }

    private org.smartledge.ai.manage.model.SystemConfigSnapshot provider() {
        SystemConfigProvider p = systemConfigProvider == null ? null : systemConfigProvider.getIfAvailable();
        return p == null ? null : p.currentSnapshot();
    }

    private <T> T required(T value, String key) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalStateException("Missing required local authentication configuration: " + key);
        }
        return value;
    }
}
