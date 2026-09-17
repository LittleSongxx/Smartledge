package org.smartledge.ai.auth.controller;

import jakarta.validation.Valid;
import org.smartledge.ai.auth.dto.UserLoginRequest;
import org.smartledge.ai.auth.service.AdminAuthService;
import org.smartledge.ai.auth.vo.AdminLoginVo;
import org.smartledge.ai.auth.vo.AdminProfileVo;
import org.smartledge.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端登录认证接口。
 */
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public ApiResponse<AdminLoginVo> login(@Valid @RequestBody UserLoginRequest request) {
        return ApiResponse.ok(adminAuthService.login(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        adminAuthService.logout();
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<AdminProfileVo> me() {
        return ApiResponse.ok(adminAuthService.currentProfile());
    }
}
