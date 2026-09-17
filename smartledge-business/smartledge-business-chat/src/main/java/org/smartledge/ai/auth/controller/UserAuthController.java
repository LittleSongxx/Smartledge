package org.smartledge.ai.auth.controller;

import jakarta.validation.Valid;
import org.smartledge.ai.auth.dto.UserLoginRequest;
import org.smartledge.ai.auth.service.UserAuthService;
import org.smartledge.ai.auth.support.AdminRequestContext;
import org.smartledge.ai.auth.support.AuthenticatedPrincipal;
import org.smartledge.ai.auth.vo.AdminLoginVo;
import org.smartledge.ai.auth.vo.AdminProfileVo;
import org.smartledge.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 用户端认证接口（{@code /api/auth/**}）。
 *
 * <p>与管理端登录分开：本入口要求 {@code chat:use} 并签发用户端用途 token。
 * 除登录本身外都需要已认证主体。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class UserAuthController {

    private final UserAuthService userAuthService;

    public UserAuthController(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    @PostMapping("/login")
    public ApiResponse<AdminLoginVo> login(@Valid @RequestBody UserLoginRequest request) {
        return ApiResponse.ok(AdminLoginVo.from(userAuthService.loginForChat(request)));
    }

    @PostMapping("/me")
    public ApiResponse<AdminProfileVo> me() {
        AuthenticatedPrincipal principal = AdminRequestContext.currentPrincipal()
            .orElseThrow(() -> new org.smartledge.ai.auth.support.AuthFailureException(401, "请先登录"));
        return ApiResponse.ok(new AdminProfileVo(
            principal.username(),
            principal.userId(),
            principal.tenantId(),
            principal.permissions()
        ));
    }
}
