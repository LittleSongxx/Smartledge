package org.smartledge.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（用户端与管理端共用同一形状）。
 *
 * <p>账号在租户内唯一，因此跨租户同名账号必须靠 {@code tenantCode} 区分；
 * 不传时按 {@code app.auth.default-tenant-code} 解析，解析不到即拒绝登录。</p>
 */
public class UserLoginRequest {

    @NotBlank(message = "请输入账号")
    private String username;

    @NotBlank(message = "请输入密码")
    private String password;

    /** 租户编码；不传表示使用默认租户编码。 */
    private String tenantCode;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }
}
