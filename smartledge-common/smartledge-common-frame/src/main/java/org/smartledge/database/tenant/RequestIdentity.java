package org.smartledge.database.tenant;

import java.util.Objects;
import java.util.Set;

/**
 * 一次请求的认证主体快照：租户 + 用户 + 角色 + 权限。
 *
 * <p>它是**请求作用域的一部分**，与租户上下文放在同一个 {@link ThreadLocal} 载体里
 * （见 {@link TenantContext}），因为它和租户有完全相同的传播语义：ThreadLocal 不跨线程，
 * 而文档可见性解析、写路径判定都发生在请求线程之外的线程上。把身份与租户分开传播，
 * 迟早会出现"租户传过去了、身份没传过去"的半个上下文。</p>
 *
 * <p>字段来源只有一个：登录时由认证层解析一次（用户表 + 角色表 + 权限表），
 * 之后全链路只消费这个快照，不再重复解释。权限与角色都是**登录时刻**的快照，
 * 改权限需要重新登录才生效 —— 这是有意的，避免每个请求都回查权限表。</p>
 *
 * <p>角色以 id 承载而不是编码：文档 ACL 表的主键是 {@code principal_id}（角色 id），
 * 用编码会让每次查询都要多做一次"编码 → id"的映射，并且角色改名就会让旧 token 失去授权。</p>
 *
 * @param tenantId    所属租户
 * @param userId      用户 id
 * @param username    登录名，仅用于展示与审计
 * @param roleIds     角色 id 集合（用于 ACL 主体匹配）
 * @param permissions 权限编码集合（用于管理端判定）
 */
public record RequestIdentity(Long tenantId,
                              Long userId,
                              String username,
                              Set<Long> roleIds,
                              Set<String> permissions) {

    public RequestIdentity {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(username, "username");
        roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** 是否持有指定权限编码。 */
    public boolean hasPermission(String permissionCode) {
        return permissionCode != null && permissions.contains(permissionCode);
    }

    /** 是否持有指定角色 id。 */
    public boolean hasRole(Long roleId) {
        return roleId != null && roleIds.contains(roleId);
    }

    /**
     * 是否持有任一管理类权限。
     *
     * <p>用于区分"用户端身份"与"管理端身份"：两个登录入口共用同一套凭据校验，
     * 差别只在入口要求的能力。</p>
     */
    public boolean hasAnyPermission(Set<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return false;
        }
        for (String permissionCode : permissionCodes) {
            if (permissions.contains(permissionCode)) {
                return true;
            }
        }
        return false;
    }

    /** 隐去口令等敏感信息，只保留可审计的标识。 */
    @Override
    public String toString() {
        return "RequestIdentity[tenantId=" + tenantId + ", userId=" + userId + ", username=" + username
            + ", roleIds=" + roleIds + ", permissionCount=" + permissions.size() + "]";
    }
}
