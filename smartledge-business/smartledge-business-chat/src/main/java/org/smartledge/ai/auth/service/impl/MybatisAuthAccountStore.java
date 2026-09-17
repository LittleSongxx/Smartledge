package org.smartledge.ai.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.smartledge.ai.auth.data.AuthPermission;
import org.smartledge.ai.auth.data.AuthRolePermission;
import org.smartledge.ai.auth.data.AuthTenant;
import org.smartledge.ai.auth.data.AuthUserAccount;
import org.smartledge.ai.auth.data.AuthUserRole;
import org.smartledge.ai.auth.mapper.AuthPermissionMapper;
import org.smartledge.ai.auth.mapper.AuthRolePermissionMapper;
import org.smartledge.ai.auth.mapper.AuthTenantMapper;
import org.smartledge.ai.auth.mapper.AuthUserAccountMapper;
import org.smartledge.ai.auth.mapper.AuthUserRoleMapper;
import org.smartledge.ai.auth.service.AuthAccountStore;
import org.smartledge.database.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 认证数据的 MyBatis 实现。
 *
 * <p>所有语句都带显式的 {@code tenant_id} 谓词，且都在对应租户作用域内执行：
 * 租户收窄由 MyBatis 拦截器负责，显式谓词负责"即使拦截器被关闭也不跨租户"。
 * 两者取同一个值，不存在某一个被绕过的可能。</p>
 */
@Service
public class MybatisAuthAccountStore implements AuthAccountStore {

    private static final int ENABLED = 1;

    private final AuthTenantMapper tenantMapper;

    private final AuthUserAccountMapper userAccountMapper;

    private final AuthUserRoleMapper userRoleMapper;

    private final AuthRolePermissionMapper rolePermissionMapper;

    private final AuthPermissionMapper permissionMapper;

    public MybatisAuthAccountStore(AuthTenantMapper tenantMapper,
                                  AuthUserAccountMapper userAccountMapper,
                                   AuthUserRoleMapper userRoleMapper,
                                   AuthRolePermissionMapper rolePermissionMapper,
                                   AuthPermissionMapper permissionMapper) {
        this.tenantMapper = tenantMapper;
        this.userAccountMapper = userAccountMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    @Override
    public Optional<Long> findEnabledTenantIdByCode(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return Optional.empty();
        }
        // 租户表没有 tenant_id 列，因此这次读取显式声明系统作用域，
        // 语义是"这是租户定义本身，不是某个租户的业务数据"。
        AuthTenant tenant = TenantContext.callAsSystem(() -> tenantMapper.selectOne(
            new LambdaQueryWrapper<AuthTenant>()
                .eq(AuthTenant::getTenantCode, tenantCode.trim())
                .eq(AuthTenant::getStatus, ENABLED)
                .last("LIMIT 1")));
        return tenant == null ? Optional.empty() : Optional.of(tenant.getId());
    }

    @Override
    public Optional<AuthAccount> findEnabledAccount(Long tenantId, String username) {
        if (tenantId == null || username == null || username.isBlank()) {
            return Optional.empty();
        }
        return TenantContext.callWith(tenantId, () -> {
            AuthUserAccount account = userAccountMapper.selectOne(
                new LambdaQueryWrapper<AuthUserAccount>()
                    .eq(AuthUserAccount::getTenantId, tenantId)
                    .eq(AuthUserAccount::getUsername, username.trim())
                    .eq(AuthUserAccount::getStatus, ENABLED)
                    .last("LIMIT 1"));
            if (account == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthAccount(
                account.getTenantId(),
                account.getId(),
                account.getUsername(),
                account.getPasswordHash(),
                account.getFailedAttempts() == null ? 0 : account.getFailedAttempts(),
                account.getLockedUntil() == null
                    ? null
                    : account.getLockedUntil().toInstant()
            ));
        });
    }

    @Override
    public List<Long> listEnabledRoleIds(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return List.of();
        }
        return TenantContext.callWith(tenantId, () -> userRoleMapper.selectList(
                new LambdaQueryWrapper<AuthUserRole>()
                    .eq(AuthUserRole::getTenantId, tenantId)
                    .eq(AuthUserRole::getUserId, userId)
                    .eq(AuthUserRole::getStatus, ENABLED))
            .stream()
            .map(AuthUserRole::getRoleId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList());
    }

    @Override
    public Set<String> listEnabledPermissionCodes(Long tenantId, Collection<Long> roleIds) {
        if (tenantId == null || roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        List<Long> distinctRoleIds = roleIds.stream()
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        if (distinctRoleIds.isEmpty()) {
            return Set.of();
        }
        return TenantContext.callWith(tenantId, () -> {
            List<Long> permissionIds = rolePermissionMapper.selectList(
                    new LambdaQueryWrapper<AuthRolePermission>()
                        .eq(AuthRolePermission::getTenantId, tenantId)
                        .in(AuthRolePermission::getRoleId, distinctRoleIds)
                        .eq(AuthRolePermission::getStatus, ENABLED))
                .stream()
                .map(AuthRolePermission::getPermissionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
            if (permissionIds.isEmpty()) {
                return Set.<String>of();
            }
            Set<String> codes = new LinkedHashSet<>();
            for (AuthPermission permission : permissionMapper.selectList(
                new LambdaQueryWrapper<AuthPermission>()
                    .in(AuthPermission::getId, permissionIds)
                    .eq(AuthPermission::getStatus, ENABLED))) {
                if (permission.getPermissionCode() != null && !permission.getPermissionCode().isBlank()) {
                    codes.add(permission.getPermissionCode());
                }
            }
            return Set.copyOf(codes);
        });
    }

    @Override
    public void recordFailedAttempt(Long tenantId, Long userId, Instant lockedUntil) {
        if (tenantId == null || userId == null) {
            return;
        }
        TenantContext.runWith(tenantId, () -> userAccountMapper.recordFailedAttempt(
            tenantId,
            userId,
            lockedUntil == null ? null : java.util.Date.from(lockedUntil)
        ));
    }

    @Override
    public void recordSuccessfulLogin(Long tenantId, Long userId, Instant loginAt) {
        if (tenantId == null || userId == null) {
            return;
        }
        TenantContext.runWith(tenantId, () -> userAccountMapper.recordSuccessfulLogin(
            tenantId,
            userId,
            java.util.Date.from(loginAt == null ? Instant.now() : loginAt)
        ));
    }
}
