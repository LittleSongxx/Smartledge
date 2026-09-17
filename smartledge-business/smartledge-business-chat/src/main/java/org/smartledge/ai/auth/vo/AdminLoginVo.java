package org.smartledge.ai.auth.vo;

import org.smartledge.ai.auth.service.LoginSession;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 登录返回值（用户端与管理端共用形状）。
 *
 * <p>两个登录入口共用同一形状与同一次转换，避免出现"两个入口的响应字段不一致"。</p>
 */
public class AdminLoginVo {

    private String username;

    private String token;

    private Long expireMinutes;

    private Long userId;

    private Long tenantId;

    /** 该主体持有的权限编码，供前端按能力渲染入口。 */
    private Set<String> permissions = new LinkedHashSet<>();

    public static AdminLoginVo from(LoginSession session) {
        return new AdminLoginVo(
            session.username(),
            session.token(),
            session.expireMinutes(),
            session.userId(),
            session.tenantId(),
            session.principal().permissions()
        );
    }

    public AdminLoginVo(String username,
                        String token,
                        Long expireMinutes,
                        Long userId,
                        Long tenantId,
                        Set<String> permissions) {
        this.username = username;
        this.token = token;
        this.expireMinutes = expireMinutes;
        this.userId = userId;
        this.tenantId = tenantId;
        this.permissions = permissions == null ? new LinkedHashSet<>() : permissions;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getExpireMinutes() {
        return expireMinutes;
    }

    public void setExpireMinutes(Long expireMinutes) {
        this.expireMinutes = expireMinutes;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions;
    }
}
