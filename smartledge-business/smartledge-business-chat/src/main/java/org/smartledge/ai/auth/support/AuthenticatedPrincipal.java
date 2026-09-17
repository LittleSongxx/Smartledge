package org.smartledge.ai.auth.support;

import org.smartledge.database.tenant.RequestIdentity;

import java.util.Objects;
import java.util.Set;

/**
 * 已认证主体：从 token 解析出来的身份快照。
 *
 * <p>与 {@link RequestIdentity} 的区别是职责：本类多带一个 token 用途（决定它能进哪一端），
 * {@link RequestIdentity} 是进入业务链路后传播的身份（审批可见性用），不含用途。
 * 两者之间只有一次转换，见 {@link #toRequestIdentity()}。</p>
 */
public record AuthenticatedPrincipal(Long tenantId,
                                     Long userId,
                                     String username,
                                     TokenAudience audience,
                                     Set<Long> roleIds,
                                     Set<String> permissions,
                                     long tokenVersion,
                                     String jti) {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(audience, "audience");
        roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        tokenVersion = tokenVersion < 1 ? 1L : tokenVersion;
    }

    public AuthenticatedPrincipal(Long tenantId,
                                  Long userId,
                                  String username,
                                  TokenAudience audience,
                                  Set<Long> roleIds,
                                  Set<String> permissions) {
        this(tenantId, userId, username, audience, roleIds, permissions, 1L, null);
    }

    public boolean hasPermission(String permissionCode) {
        return permissionCode != null && permissions.contains(permissionCode);
    }

    /** 投影成业务链路传播用的身份快照（租户取自本主体）。 */
    public RequestIdentity toRequestIdentity() {
        return new RequestIdentity(tenantId, userId, username, roleIds, permissions);
    }

    @Override
    public String toString() {
        return "AuthenticatedPrincipal[tenantId=" + tenantId + ", userId=" + userId
            + ", username=" + username + ", audience=" + audience + "]";
    }
}
