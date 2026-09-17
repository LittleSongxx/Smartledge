package org.smartledge.ai.manage.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.smartledge.ai.manage.dto.KnowledgeBaseConfigUpdateDto;
import org.smartledge.ai.manage.dto.KnowledgeBaseDeleteDto;
import org.smartledge.ai.manage.dto.KnowledgeBaseDetailDto;
import org.smartledge.ai.manage.dto.KnowledgeBaseSaveDto;
import org.smartledge.ai.manage.service.KnowledgeBaseManageService;
import org.smartledge.ai.manage.vo.KnowledgeBaseItemVo;
import org.smartledge.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.smartledge.ai.auth.support.RequiresPermission;

@AllArgsConstructor
@RestController
@RequestMapping("/manage/knowledge/base")
@RequiresPermission("kb:read")
public class KnowledgeBaseManageController {

    private final KnowledgeBaseManageService knowledgeBaseManageService;

    @Operation(summary = "保存知识库")
    @PostMapping("/save")
    @RequiresPermission("kb:write")
    public ApiResponse<KnowledgeBaseItemVo> save(@Valid @RequestBody KnowledgeBaseSaveDto dto) {
        return ApiResponse.ok(knowledgeBaseManageService.save(dto));
    }

    @Operation(summary = "删除知识库")
    @PostMapping("/delete")
    @RequiresPermission("kb:delete")
    public ApiResponse<Boolean> delete(@Valid @RequestBody KnowledgeBaseDeleteDto dto) {
        return ApiResponse.ok(knowledgeBaseManageService.delete(dto));
    }

    @Operation(summary = "查询知识库列表")
    @PostMapping("/list")
    public ApiResponse<List<KnowledgeBaseItemVo>> list() {
        return ApiResponse.ok(knowledgeBaseManageService.list());
    }

    @Operation(summary = "查询知识库详情")
    @PostMapping("/detail")
    public ApiResponse<KnowledgeBaseItemVo> detail(@Valid @RequestBody KnowledgeBaseDetailDto dto) {
        return ApiResponse.ok(knowledgeBaseManageService.detail(dto));
    }

    @Operation(summary = "更新知识库检索配置")
    @PostMapping("/config/update")
    @RequiresPermission("kb:write")
    public ApiResponse<KnowledgeBaseItemVo> updateConfig(@Valid @RequestBody KnowledgeBaseConfigUpdateDto dto) {
        return ApiResponse.ok(knowledgeBaseManageService.updateConfig(dto));
    }
}
