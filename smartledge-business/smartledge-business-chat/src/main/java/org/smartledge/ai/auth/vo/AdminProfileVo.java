package org.smartledge.ai.auth.vo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 当前登录主体信息（用户端与管理端共用形状）。
 */
public class AdminProfileVo {

    private String username;

    private Long userId;

    private Long tenantId;

    private Set<String> permissions = new LinkedHashSet<>();

    public AdminProfileVo(String username, Long userId, Long tenantId, Set<String> permissions) {
        this.username = username;
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
