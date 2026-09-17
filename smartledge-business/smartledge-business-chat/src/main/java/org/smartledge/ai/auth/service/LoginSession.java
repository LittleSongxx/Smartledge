package org.smartledge.ai.auth.service;

import org.smartledge.ai.auth.support.AuthenticatedPrincipal;

/**
 * 登录结果：已认证主体 + 该主体本端用途的 token。
 */
public record LoginSession(AuthenticatedPrincipal principal,
                           String token,
                           long expireMinutes) {

    public String username() {
        return principal.username();
    }

    public Long userId() {
        return principal.userId();
    }

    public Long tenantId() {
        return principal.tenantId();
    }
}
