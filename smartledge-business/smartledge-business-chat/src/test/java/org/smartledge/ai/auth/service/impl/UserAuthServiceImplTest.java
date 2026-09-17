package org.smartledge.ai.auth.service.impl;

import org.smartledge.ai.auth.config.LoginSecurityProperties;
import org.smartledge.ai.auth.config.AdminAuthProperties;
import org.smartledge.ai.auth.dto.UserLoginRequest;
import org.smartledge.ai.auth.service.AuthAccountStore;
import org.smartledge.ai.auth.service.LoginSession;
import org.smartledge.ai.auth.support.AuthenticatedPrincipal;
import org.smartledge.ai.auth.support.JwtTokenService;
import org.smartledge.ai.auth.support.PasswordVerifier;
import org.smartledge.ai.auth.support.TokenAudience;
import org.smartledge.exception.SuperAgentFrameException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录认证的不变量测试。
 *
 * <p>对应 B3 的登录要求：BCrypt 校验、失败锁定、两个入口的能力差异、以及 fail closed
 * （未知账号、未知租户、缺权限一律拒绝，且不产生任何副作用）。</p>
 *
 * <p>用内存假仓储而不是 mock：本测试要断言"失败时到底写了什么"，
 * 以及"未知账号时什么都没写"，用假实现比 verify 更直观。</p>
 */
class UserAuthServiceImplTest {

    private static final String USER_HASH =
        "$2b$10$I4mwXI8OW5xGIyIwufLfUeJcb0CWLSeOh.VE0vcZkhL6vqnn4Tnx6";

    private RecordingAuthAccountStore store;

    private UserAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        store = new RecordingAuthAccountStore();
        LoginSecurityProperties security = new LoginSecurityProperties();
        security.setMaxFailedAttempts(5);
        security.setLockMinutes(15);
        security.setDefaultTenantCode("default");
        AdminAuthProperties token = new AdminAuthProperties();
        token.setTokenSecret("unit-test-secret");
        token.setTokenExpireMinutes(720L);
        service = new UserAuthServiceImpl(store, new PasswordVerifier(), new JwtTokenService(token), security);
    }

    @Test
    @DisplayName("用户端登录成功：token 带租户、用户与角色，且返回该主体的权限")
    void chatLoginSucceedsForOrdinaryUser() {
        store.addTenant("default", 1L);
        store.addAccount(1L, 3L, "alice", USER_HASH, 0, null);
        store.addRoles(1L, 3L, List.of(3L));
        store.addPermissions(1L, Map.of(3L, Set.of("chat:use", "document:read")));

        LoginSession session = service.loginForChat(request("alice", "user123456", null));

        assertThat(session.token()).isNotBlank();
        assertThat(session.expireMinutes()).isEqualTo(720L);
        AuthenticatedPrincipal principal = session.principal();
        assertThat(principal.tenantId()).isEqualTo(1L);
        assertThat(principal.userId()).isEqualTo(3L);
        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.audience()).isEqualTo(TokenAudience.CHAT);
        assertThat(principal.roleIds()).containsExactly(3L);
        assertThat(principal.permissions()).containsExactlyInAnyOrder("chat:use", "document:read");
        assertThat(store.successfulLogins).containsExactly("1:3");
        assertThat(store.failedAttempts).isEmpty();
    }

    @Test
    @DisplayName("管理端登录要求 console:access：普通用户即使口令正确也拒绝")
    void adminLoginRequiresConsolePermission() {
        store.addTenant("default", 1L);
        store.addAccount(1L, 3L, "alice", USER_HASH, 0, null);
        store.addRoles(1L, 3L, List.of(3L));
        store.addPermissions(1L, Map.of(3L, Set.of("chat:use", "document:read")));

        assertThatThrownBy(() -> service.loginForAdmin(request("alice", "user123456", null)))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不是管理账号");
        // 权限不足发生在凭据校验通过之后：登录成功状态被记录，但没有签发 token。
        assertThat(store.successfulLogins).containsExactly("1:3");
    }

    @Test
    @DisplayName("管理员登录成功：ADMIN 角色拿到全部权限")
    void adminLoginSucceedsForManagementRole() {
        store.addTenant("default", 1L);
        store.addAccount(1L, 1L, "admin", USER_HASH, 0, null);
        store.addRoles(1L, 1L, List.of(1L));
        store.addPermissions(1L, Map.of(1L, Set.of("console:access", "kb:write", "chat:use")));

        LoginSession session = service.loginForAdmin(request("admin", "user123456", null));

        assertThat(session.principal().audience()).isEqualTo(TokenAudience.ADMIN);
        assertThat(session.principal().permissions()).contains("console:access");
    }

    @Test
    @DisplayName("口令错误：只累加失败计数，不签发 token、不记录登录成功")
    void wrongPasswordIsRejectedAndCounted() {
        store.addTenant("default", 1L);
        store.addAccount(1L, 3L, "alice", USER_HASH, 2, null);

        assertThatThrownBy(() -> service.loginForChat(request("alice", "wrong", null)))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("账号或密码不正确");

        assertThat(store.failedAttempts).containsExactly("1:3:null");
        assertThat(store.successfulLogins).isEmpty();
    }

    @Test
    @DisplayName("连续失败达到阈值即写入锁定截止时间，且下一次登录直接拒绝")
    void failedAttemptsLockTheAccountAtThreshold() {
        store.addTenant("default", 1L);
        store.addAccount(1L, 3L, "alice", USER_HASH, 4, null);
        store.addRoles(1L, 3L, List.of(3L));
        store.addPermissions(1L, Map.of(3L, Set.of("chat:use")));

        assertThatThrownBy(() -> service.loginForChat(request("alice", "wrong", null)))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("账号或密码不正确");

        assertThat(store.failedAttempts).hasSize(1);
        Instant lockedUntil = store.lockedUntilOf(1L, 3L);
        assertThat(lockedUntil).isNotNull();
        assertThat(lockedUntil).isAfter(Instant.now().plus(14, ChronoUnit.MINUTES));

        // 锁定生效后即使口令正确也拒绝
        assertThatThrownBy(() -> service.loginForChat(request("alice", "user123456", null)))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("账号已锁定");
        assertThat(store.successfulLogins).isEmpty();
    }

    @Test
    @DisplayName("锁定期已过时不再拦截，正确口令可以登录并清零失败计数")
    void expiredLockAllowsLogin() {
        store.addTenant("default", 1L);
        store.addAccount(1L, 3L, "alice", USER_HASH, 5, Instant.now().minus(1, ChronoUnit.MINUTES));
        store.addRoles(1L, 3L, List.of(3L));
        store.addPermissions(1L, Map.of(3L, Set.of("chat:use")));

        LoginSession session = service.loginForChat(request("alice", "user123456", null));

        assertThat(session.principal().userId()).isEqualTo(3L);
        assertThat(store.successfulLogins).containsExactly("1:3");
    }

    @Test
    @DisplayName("账号不存在与口令错误返回同一条文案，且都不写任何状态")
    void unknownAccountFailsClosedWithoutSideEffects() {
        store.addTenant("default", 1L);

        assertThatThrownBy(() -> service.loginForChat(request("nobody", "user123456", null)))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("账号或密码不正确");

        // 查找确实发生（否则无法区分"账号不存在"与"没查"），但没有任何写入。
        assertThat(store.loadedAccounts).containsExactly("1:nobody");
        assertThat(store.failedAttempts).isEmpty();
        assertThat(store.successfulLogins).isEmpty();
    }

    @Test
    @DisplayName("租户编码解析不到时拒绝登录，不跨租户查找账号")
    void unknownTenantFailsClosed() {
        store.addAccount(1L, 3L, "alice", USER_HASH, 0, null);
        store.addRoles(1L, 3L, List.of(3L));
        store.addPermissions(1L, Map.of(3L, Set.of("chat:use")));

        assertThatThrownBy(() -> service.loginForChat(request("alice", "user123456", "nope")))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("账号或密码不正确");
        assertThat(store.loadedAccounts).isEmpty();
    }

    @Test
    @DisplayName("未指定租户编码时按默认编码解析；同名账号在不同租户各自独立")
    void tenantCodeSelectsAccountWithinTenant() {
        store.addTenant("default", 1L);
        store.addTenant("demo-b", 2L);
        store.addAccount(1L, 3L, "alice", USER_HASH, 0, null);
        store.addRoles(1L, 3L, List.of(3L));
        store.addPermissions(1L, Map.of(3L, Set.of("chat:use")));
        store.addAccount(2L, 9L, "alice", USER_HASH, 0, null);
        store.addRoles(2L, 9L, List.of(5L));
        store.addPermissions(2L, Map.of(5L, Set.of("chat:use")));

        LoginSession defaultTenant = service.loginForChat(request("alice", "user123456", null));
        LoginSession tenantB = service.loginForChat(request("alice", "user123456", "demo-b"));

        assertThat(defaultTenant.principal().userId()).isEqualTo(3L);
        assertThat(tenantB.principal().tenantId()).isEqualTo(2L);
        assertThat(tenantB.principal().userId()).isEqualTo(9L);
        assertThat(tenantB.principal().roleIds()).containsExactly(5L);
    }

    @Test
    @DisplayName("用户端登录要求 chat:use：没有该权限的账号被拒绝")
    void chatLoginRequiresChatPermission() {
        store.addTenant("default", 1L);
        store.addAccount(1L, 7L, "locked-out", USER_HASH, 0, null);
        store.addRoles(1L, 7L, List.of());
        store.addPermissions(1L, Map.of());

        assertThatThrownBy(() -> service.loginForChat(request("locked-out", "user123456", null)))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("没有对话权限");
    }

    private UserLoginRequest request(String username, String password, String tenantCode) {
        UserLoginRequest request = new UserLoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setTenantCode(tenantCode);
        return request;
    }

    /** 记录副作用的认证数据假实现。 */
    private static final class RecordingAuthAccountStore implements AuthAccountStore {

        private final Map<String, Long> tenants = new LinkedHashMap<>();

        private final Map<String, AuthAccount> accounts = new LinkedHashMap<>();

        private final Map<String, List<Long>> roles = new LinkedHashMap<>();

        private final Map<String, Set<String>> permissionsByRole = new LinkedHashMap<>();

        private final List<String> loadedAccounts = new ArrayList<>();

        private final List<String> failedAttempts = new ArrayList<>();

        private final List<String> successfulLogins = new ArrayList<>();

        private final Map<String, Instant> lockedUntil = new LinkedHashMap<>();

        void addTenant(String code, Long tenantId) {
            tenants.put(code, tenantId);
        }

        void addAccount(Long tenantId, Long userId, String username, String hash, int failed, Instant lockedUntilAt) {
            accounts.put(tenantId + ":" + username,
                new AuthAccount(tenantId, userId, username, hash, failed, lockedUntilAt));
        }

        void addRoles(Long tenantId, Long userId, List<Long> roleIds) {
            roles.put(tenantId + ":" + userId, roleIds);
        }

        void addPermissions(Long tenantId, Map<Long, Set<String>> byRole) {
            byRole.forEach((roleId, codes) -> permissionsByRole.put(tenantId + ":" + roleId, codes));
        }

        Instant lockedUntilOf(Long tenantId, Long userId) {
            return lockedUntil.get(tenantId + ":" + userId);
        }

        @Override
        public Optional<Long> findEnabledTenantIdByCode(String tenantCode) {
            return Optional.ofNullable(tenants.get(tenantCode));
        }

        @Override
        public Optional<AuthAccount> findEnabledAccount(Long tenantId, String username) {
            if (tenantId == null) {
                return Optional.empty();
            }
            String key = tenantId + ":" + username;
            loadedAccounts.add(key);
            return Optional.ofNullable(accounts.get(key));
        }

        @Override
        public List<Long> listEnabledRoleIds(Long tenantId, Long userId) {
            return roles.getOrDefault(tenantId + ":" + userId, List.of());
        }

        @Override
        public Set<String> listEnabledPermissionCodes(Long tenantId, Collection<Long> roleIds) {
            Set<String> codes = new LinkedHashSet<>();
            roleIds.forEach(roleId -> codes.addAll(permissionsByRole.getOrDefault(tenantId + ":" + roleId, Set.of())));
            return Set.copyOf(codes);
        }

        @Override
        public void recordFailedAttempt(Long tenantId, Long userId, Instant lockedUntilAt) {
            failedAttempts.add(tenantId + ":" + userId + ":" + (lockedUntilAt == null ? "null" : "locked"));
            if (lockedUntilAt != null) {
                lockedUntil.put(tenantId + ":" + userId, lockedUntilAt);
            }
            // 把写入反映到后续读取：锁定是账号状态，下一次登录必须能看到它。
            accounts.replaceAll((key, account) -> account.tenantId().equals(tenantId)
                && account.userId().equals(userId)
                ? new AuthAccount(account.tenantId(), account.userId(), account.username(),
                    account.passwordHash(), account.failedAttempts() + 1, lockedUntilAt)
                : account);
        }

        @Override
        public void recordSuccessfulLogin(Long tenantId, Long userId, Instant loginAt) {
            successfulLogins.add(tenantId + ":" + userId);
        }
    }
}
