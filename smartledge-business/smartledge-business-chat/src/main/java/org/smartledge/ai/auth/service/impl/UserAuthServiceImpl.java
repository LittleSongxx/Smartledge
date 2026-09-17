package org.smartledge.ai.auth.service.impl;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.auth.config.LoginSecurityProperties;
import org.smartledge.ai.auth.dto.UserLoginRequest;
import org.smartledge.ai.auth.service.AuthAccountStore;
import org.smartledge.ai.auth.service.LoginSession;
import org.smartledge.ai.auth.service.UserAuthService;
import org.smartledge.ai.auth.support.AuthFailureException;
import org.smartledge.ai.auth.support.AuthenticatedPrincipal;
import org.smartledge.ai.auth.support.JwtTokenService;
import org.smartledge.ai.auth.support.PasswordVerifier;
import org.smartledge.ai.auth.support.TokenAudience;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 登录认证实现。
 *
 * <p>凭据校验顺序是刻意的：租户 → 账号 → 锁定 → 口令 → 能力。任何一步失败都返回同一条
 * 401 文案，不区分"账号不存在"与"口令错误"；未知账号也要执行一次 BCrypt 比较，
 * 因此接口耗时不泄漏账号是否存在。</p>
 */
@Service
public class UserAuthServiceImpl implements UserAuthService {

    /** 用户端登录要求的能力。 */
    private static final String CHAT_PERMISSION = "chat:use";

    /** 管理端登录要求的能力：只有管理角色持有。 */
    private static final String CONSOLE_PERMISSION = "console:access";

    private static final String CREDENTIAL_ERROR = "账号或密码不正确";

    private final AuthAccountStore authAccountStore;

    private final PasswordVerifier passwordVerifier;

    private final JwtTokenService jwtTokenService;

    private final LoginSecurityProperties loginSecurityProperties;

    public UserAuthServiceImpl(AuthAccountStore authAccountStore,
                               PasswordVerifier passwordVerifier,
                               JwtTokenService jwtTokenService,
                               LoginSecurityProperties loginSecurityProperties) {
        this.authAccountStore = authAccountStore;
        this.passwordVerifier = passwordVerifier;
        this.jwtTokenService = jwtTokenService;
        this.loginSecurityProperties = loginSecurityProperties;
    }

    @Override
    public LoginSession loginForChat(UserLoginRequest request) {
        AuthenticatedPrincipal principal = authenticate(request, TokenAudience.CHAT);
        requirePermission(principal, CHAT_PERMISSION, "当前账号没有对话权限，请联系管理员开通");
        return issue(principal);
    }

    @Override
    public LoginSession loginForAdmin(UserLoginRequest request) {
        AuthenticatedPrincipal principal = authenticate(request, TokenAudience.ADMIN);
        requirePermission(principal, CONSOLE_PERMISSION, "当前账号不是管理账号，无法登录管理台");
        return issue(principal);
    }

    private LoginSession issue(AuthenticatedPrincipal principal) {
        return new LoginSession(principal, jwtTokenService.generateToken(principal),
            jwtTokenService.tokenExpireMinutes());
    }

    private void requirePermission(AuthenticatedPrincipal principal, String permissionCode, String message) {
        if (!principal.hasPermission(permissionCode)) {
            throw new AuthFailureException(403, message);
        }
    }

    private AuthenticatedPrincipal authenticate(UserLoginRequest request, TokenAudience audience) {
        String username = request == null ? null : StrUtil.trim(request.getUsername());
        String tenantCode = resolveTenantCode(request == null ? null : request.getTenantCode());
        if (StrUtil.isBlank(username) || request.getPassword() == null) {
            throw new AuthFailureException(401, CREDENTIAL_ERROR);
        }
        Long tenantId = authAccountStore.findEnabledTenantIdByCode(tenantCode).orElse(null);
        AuthAccountStore.AuthAccount account = tenantId == null
            ? null
            : authAccountStore.findEnabledAccount(tenantId, username).orElse(null);

        Instant now = Instant.now();
        if (account != null && account.lockedAt(now)) {
            // 锁定期内即使口令正确也拒绝：锁定是账号级状态，不是口令级状态。
            throw new AuthFailureException(423,
                "账号已锁定，请在 " + loginSecurityProperties.getLockMinutes() + " 分钟后重试");
        }

        boolean credentialMatched = passwordVerifier.matches(
            request.getPassword(),
            account == null ? null : account.passwordHash()
        );
        if (account == null || !credentialMatched) {
            if (account != null) {
                registerFailedAttempt(account, now);
            }
            throw new AuthFailureException(401, CREDENTIAL_ERROR);
        }

        authAccountStore.recordSuccessfulLogin(account.tenantId(), account.userId(), now);
        List<Long> roleIds = authAccountStore.listEnabledRoleIds(account.tenantId(), account.userId());
        Set<String> permissions = authAccountStore.listEnabledPermissionCodes(account.tenantId(), roleIds);
        return new AuthenticatedPrincipal(
            account.tenantId(),
            account.userId(),
            account.username(),
            audience,
            Set.copyOf(roleIds),
            permissions,
            account.tokenVersion(),
            null
        );
    }

    private void registerFailedAttempt(AuthAccountStore.AuthAccount account, Instant now) {
        int maxFailedAttempts = Math.max(1, loginSecurityProperties.getMaxFailedAttempts());
        int nextFailedAttempts = account.failedAttempts() + 1;
        Instant lockedUntil = nextFailedAttempts >= maxFailedAttempts
            ? now.plusSeconds(Math.max(1, loginSecurityProperties.getLockMinutes()) * 60)
            : null;
        authAccountStore.recordFailedAttempt(account.tenantId(), account.userId(), lockedUntil);
    }

    private String resolveTenantCode(String requestedTenantCode) {
        String tenantCode = StrUtil.trim(requestedTenantCode);
        return StrUtil.isBlank(tenantCode) ? loginSecurityProperties.getDefaultTenantCode() : tenantCode;
    }
}
