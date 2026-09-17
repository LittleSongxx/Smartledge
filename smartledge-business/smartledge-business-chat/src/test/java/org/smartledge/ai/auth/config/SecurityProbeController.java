package org.smartledge.ai.auth.config;

import org.smartledge.ai.auth.support.RequiresPermission;
import org.smartledge.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 授权规则测试用的探针控制器（与真实路径形状一致，避免真实控制器带来无关依赖）。
 *
 * <p>路径覆盖三类：用户端 {@code /api/chat/**}、管理端 {@code /manage/**}、
 * 以及曾经被排除鉴权的评测快照导出路径。</p>
 */
@RestController
class SecurityProbeController {

    @PostMapping("/api/chat/probe")
    public ApiResponse<String> chat() {
        return ApiResponse.ok("chat-ok");
    }

    @PostMapping("/api/auth/me")
    public ApiResponse<String> me() {
        return ApiResponse.ok("me-ok");
    }

    @PostMapping("/manage/probe")
    @RequiresPermission("observe:read")
    public ApiResponse<String> manage() {
        return ApiResponse.ok("manage-ok");
    }

    @PostMapping("/manage/probe/undeclared")
    public ApiResponse<String> undeclared() {
        return ApiResponse.ok("should-not-run");
    }

    @PostMapping("/manage/evaluation/exchange/snapshot/query")
    @RequiresPermission("observe:read")
    public ApiResponse<String> snapshot() {
        return ApiResponse.ok("snapshot-ok");
    }
}
