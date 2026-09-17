package org.smartledge.ai.auth.service.impl;

import org.smartledge.ai.auth.dto.UserLoginRequest;
import org.smartledge.ai.auth.service.AdminAuthService;
import org.smartledge.ai.auth.service.AuthAccountStore;
import org.smartledge.ai.auth.service.UserAuthService;
import org.smartledge.ai.auth.support.AdminRequestContext;
import org.smartledge.ai.auth.support.AuthenticatedPrincipal;
import org.smartledge.ai.auth.vo.AdminLoginVo;
import org.smartledge.ai.auth.vo.AdminProfileVo;
import org.smartledge.ai.auth.support.AuthFailureException;
import org.springframework.stereotype.Service;

/**
 * 管理端登录认证实现：凭据校验与用户端共用同一条路径，差别只在要求的权限与 token 用途。
 */
@Service
public class AdminAuthServiceImpl implements AdminAuthService {

    private final UserAuthService userAuthService;

    private final AuthAccountStore authAccountStore;

    public AdminAuthServiceImpl(UserAuthService userAuthService, AuthAccountStore authAccountStore) {
        this.userAuthService = userAuthService;
        this.authAccountStore = authAccountStore;
    }

    @Override
    public AdminLoginVo login(UserLoginRequest request) {
        return AdminLoginVo.from(userAuthService.loginForAdmin(request));
    }

    @Override
    public AdminProfileVo currentProfile() {
        AuthenticatedPrincipal principal = AdminRequestContext.currentPrincipal()
            .orElseThrow(() -> new AuthFailureException(401, "请先登录"));
        return new AdminProfileVo(
            principal.username(),
            principal.userId(),
            principal.tenantId(),
            principal.permissions()
        );
    }

    @Override
    public void logout() {
        AuthenticatedPrincipal principal = AdminRequestContext.currentPrincipal()
            .orElseThrow(() -> new AuthFailureException(401, "请先登录"));
        authAccountStore.incrementTokenVersion(principal.tenantId(), principal.userId());
    }
}
