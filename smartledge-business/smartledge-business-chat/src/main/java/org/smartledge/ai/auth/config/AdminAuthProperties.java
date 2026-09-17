package org.smartledge.ai.auth.config;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.ObjectProvider;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.stereotype.Component;

/**
 * 后台管理登录配置。
 */
@Data
@Component
public class AdminAuthProperties {

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObjectProvider<SystemConfigProvider> systemConfigProvider;

    /**
     * JWT 签名密钥。
     */
    private String tokenSecret;

    /**
     * token 有效期，单位分钟。
     */
    private Long tokenExpireMinutes;

    public String getTokenSecret() { var p = provider(); return required(p == null ? tokenSecret : p.getAdminAuth().getTokenSecret(), "adminAuth.tokenSecret"); }
    public Long getTokenExpireMinutes() { var p = provider(); return required(p == null ? tokenExpireMinutes : p.getAdminAuth().getTokenExpireMinutes(), "adminAuth.tokenExpireMinutes"); }
    private org.smartledge.ai.manage.model.SystemConfigSnapshot provider() {
        SystemConfigProvider p = systemConfigProvider == null ? null : systemConfigProvider.getIfAvailable();
        return p == null ? null : p.currentSnapshot();
    }

    private <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalStateException("Missing required database configuration: " + key);
        }
        return value;
    }
}
