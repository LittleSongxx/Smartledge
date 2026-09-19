package org.smartledge.ai.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import org.smartledge.ai.auth.dto.TenantMemberPageQueryDto;
import org.smartledge.ai.auth.dto.TenantMemberSaveDto;
import org.smartledge.ai.auth.dto.TenantMemberStatusUpdateDto;
import org.smartledge.ai.auth.service.TenantMemberManageService;
import org.smartledge.ai.auth.support.RequiresPermission;
import org.smartledge.ai.auth.vo.TenantMemberItemVo;
import org.smartledge.ai.auth.vo.TenantMemberPageQueryVo;
import org.smartledge.ai.auth.vo.TenantRoleItemVo;
import org.smartledge.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户内成员与角色管理接口（S23-B2）。
 *
 * <p>准入有两层：filter chain 要求管理端用途 token，本控制器类级声明 {@code user:manage}
 * 由 {@code ManagePermissionInterceptor} 逐调用判定。接口不接收租户参数 —— 作用域只来自认证主体。</p>
 */
@AllArgsConstructor
@RestController
@RequestMapping("/manage/tenant/member")
@RequiresPermission("user:manage")
public class TenantMemberManageController {

    private final TenantMemberManageService tenantMemberManageService;

    @Operation(summary = "分页查询租户内成员")
    @PostMapping("/page/query")
    public ApiResponse<TenantMemberPageQueryVo> page(@RequestBody(required = false) TenantMemberPageQueryDto dto) {
        return ApiResponse.ok(tenantMemberManageService.queryPage(dto));
    }

    @Operation(summary = "新建或编辑成员（含角色分配）")
    @PostMapping("/save")
    public ApiResponse<TenantMemberItemVo> save(@RequestBody TenantMemberSaveDto dto) {
        return ApiResponse.ok(tenantMemberManageService.save(dto));
    }

    @Operation(summary = "启用或停用成员")
    @PostMapping("/status/update")
    public ApiResponse<TenantMemberItemVo> updateStatus(@RequestBody TenantMemberStatusUpdateDto dto) {
        return ApiResponse.ok(tenantMemberManageService.updateStatus(dto));
    }

    @Operation(summary = "查询当前租户可分配的角色")
    @PostMapping("/role/list")
    public ApiResponse<List<TenantRoleItemVo>> roleList() {
        return ApiResponse.ok(tenantMemberManageService.listAssignableRoles());
    }
}
