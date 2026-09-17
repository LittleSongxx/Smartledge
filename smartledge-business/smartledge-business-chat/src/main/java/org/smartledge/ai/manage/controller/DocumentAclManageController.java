package org.smartledge.ai.manage.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import org.smartledge.ai.auth.support.RequiresPermission;
import org.smartledge.ai.manage.dto.DocumentAclGrantDto;
import org.smartledge.ai.manage.dto.DocumentAclQueryDto;
import org.smartledge.ai.manage.dto.DocumentAclRevokeDto;
import org.smartledge.ai.manage.service.DocumentAclManageService;
import org.smartledge.ai.manage.vo.DocumentAclPrincipalVo;
import org.smartledge.ai.manage.vo.DocumentAclViewVo;
import org.smartledge.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档授权管理接口（S23-B3）。
 *
 * <p>类级声明 {@code document:acl:manage}（"能不能做授权管理"）；每份文档还必须在文档级 ACL 上
 * 持有 MANAGE（"能不能对这份文档授权"），后者在服务层判定。两层都满足才放行。</p>
 */
@AllArgsConstructor
@RestController
@RequestMapping("/manage/document/acl")
@RequiresPermission("document:acl:manage")
public class DocumentAclManageController {

    private final DocumentAclManageService documentAclManageService;

    @Operation(summary = "查询文档授权列表")
    @PostMapping("/query")
    public ApiResponse<DocumentAclViewVo> query(@RequestBody DocumentAclQueryDto dto) {
        return ApiResponse.ok(documentAclManageService.query(dto));
    }

    @Operation(summary = "查询可授权的用户与角色")
    @PostMapping("/principal/list")
    public ApiResponse<DocumentAclPrincipalVo> principalList() {
        return ApiResponse.ok(documentAclManageService.listAssignablePrincipals());
    }

    @Operation(summary = "授予文档权限或修改已有权限")
    @PostMapping("/grant")
    public ApiResponse<DocumentAclViewVo> grant(@RequestBody DocumentAclGrantDto dto) {
        return ApiResponse.ok(documentAclManageService.grant(dto));
    }

    @Operation(summary = "撤销文档授权")
    @PostMapping("/revoke")
    public ApiResponse<DocumentAclViewVo> revoke(@RequestBody DocumentAclRevokeDto dto) {
        return ApiResponse.ok(documentAclManageService.revoke(dto));
    }
}
