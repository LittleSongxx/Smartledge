package org.smartledge.ai.manage.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.smartledge.ai.manage.dto.DocumentProfileBatchRegenerateDto;
import org.smartledge.ai.manage.dto.DocumentProfileDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentProfileRegenerateDto;
import org.smartledge.ai.manage.dto.KnowledgeRouteTraceQueryDto;
import org.smartledge.ai.manage.dto.KnowledgeScopeDeleteDto;
import org.smartledge.ai.manage.dto.KnowledgeScopeQueryDto;
import org.smartledge.ai.manage.dto.KnowledgeScopeSaveDto;
import org.smartledge.ai.manage.dto.KnowledgeTopicDeleteDto;
import org.smartledge.ai.manage.dto.KnowledgeTopicQueryDto;
import org.smartledge.ai.manage.dto.KnowledgeTopicSaveDto;
import org.smartledge.ai.manage.dto.TopicDocumentRelationListQueryDto;
import org.smartledge.ai.manage.dto.TopicDocumentRelationRemoveDto;
import org.smartledge.ai.manage.dto.TopicDocumentRelationSaveDto;
import org.smartledge.ai.manage.service.KnowledgeManageService;
import org.smartledge.ai.manage.vo.DocumentProfileVo;
import org.smartledge.ai.manage.vo.KnowledgeRouteTracePageVo;
import org.smartledge.ai.manage.vo.KnowledgeScopeItemVo;
import org.smartledge.ai.manage.vo.KnowledgeTopicItemVo;
import org.smartledge.ai.manage.vo.TopicDocumentRelationItemVo;
import org.smartledge.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.smartledge.ai.auth.support.RequiresPermission;

/**
 * @description: 控制层
 * @author: Song
 **/
@RestController
@RequestMapping("/manage/knowledge")
@RequiresPermission("kb:read")
public class KnowledgeManageController {

    private final KnowledgeManageService knowledgeManageService;

    public KnowledgeManageController(KnowledgeManageService knowledgeManageService) {
        this.knowledgeManageService = knowledgeManageService;
    }

    @Operation(summary = "保存知识范围节点")
    @PostMapping("/scope/save")
    @RequiresPermission("kb:write")
    public ApiResponse<KnowledgeScopeItemVo> saveScope(@Valid @RequestBody KnowledgeScopeSaveDto dto) {
        return ApiResponse.ok(knowledgeManageService.saveScope(dto));
    }

    @Operation(summary = "删除知识范围节点")
    @PostMapping("/scope/delete")
    @RequiresPermission("kb:write")
    public ApiResponse<Boolean> deleteScope(@Valid @RequestBody KnowledgeScopeDeleteDto dto) {
        return ApiResponse.ok(knowledgeManageService.deleteScope(dto));
    }

    @Operation(summary = "查询知识范围列表")
    @PostMapping("/scope/list")
    public ApiResponse<List<KnowledgeScopeItemVo>> listScopes(@RequestBody(required = false) KnowledgeScopeQueryDto dto) {
        return ApiResponse.ok(knowledgeManageService.listScopes(dto == null ? new KnowledgeScopeQueryDto() : dto));
    }

    @Operation(summary = "保存知识主题节点")
    @PostMapping("/topic/save")
    @RequiresPermission("kb:write")
    public ApiResponse<KnowledgeTopicItemVo> saveTopic(@Valid @RequestBody KnowledgeTopicSaveDto dto) {
        return ApiResponse.ok(knowledgeManageService.saveTopic(dto));
    }

    @Operation(summary = "删除知识主题节点")
    @PostMapping("/topic/delete")
    @RequiresPermission("kb:write")
    public ApiResponse<Boolean> deleteTopic(@Valid @RequestBody KnowledgeTopicDeleteDto dto) {
        return ApiResponse.ok(knowledgeManageService.deleteTopic(dto));
    }

    @Operation(summary = "查询知识主题列表")
    @PostMapping("/topic/list")
    public ApiResponse<List<KnowledgeTopicItemVo>> listTopics(@RequestBody(required = false) KnowledgeTopicQueryDto dto) {
        return ApiResponse.ok(knowledgeManageService.listTopics(dto == null ? new KnowledgeTopicQueryDto() : dto));
    }

    @Operation(summary = "查询文档画像详情")
    @PostMapping("/document/profile/detail")
    public ApiResponse<DocumentProfileVo> queryProfile(@Valid @RequestBody DocumentProfileDetailQueryDto dto) {
        return ApiResponse.ok(knowledgeManageService.queryProfile(dto));
    }

    @Operation(summary = "重新生成文档画像")
    @PostMapping("/document/profile/regenerate")
    @RequiresPermission("document:write")
    public ApiResponse<DocumentProfileVo> regenerateProfile(@Valid @RequestBody DocumentProfileRegenerateDto dto) {
        return ApiResponse.ok(knowledgeManageService.regenerateProfile(dto));
    }

    @Operation(summary = "批量重新生成文档画像")
    @PostMapping("/document/profile/batch/regenerate")
    @RequiresPermission("document:write")
    public ApiResponse<List<DocumentProfileVo>> batchRegenerateProfiles(@Valid @RequestBody DocumentProfileBatchRegenerateDto dto) {
        return ApiResponse.ok(knowledgeManageService.batchRegenerateProfiles(dto));
    }

    @Operation(summary = "查询主题文档关联")
    @PostMapping("/topic/document/list")
    public ApiResponse<List<TopicDocumentRelationItemVo>> listTopicDocuments(@RequestBody(required = false) TopicDocumentRelationListQueryDto dto) {
        return ApiResponse.ok(knowledgeManageService.listTopicDocuments(dto == null ? new TopicDocumentRelationListQueryDto() : dto));
    }

    @Operation(summary = "保存主题文档关联")
    @PostMapping("/topic/document/save")
    @RequiresPermission("kb:write")
    public ApiResponse<TopicDocumentRelationItemVo> saveTopicDocumentRelation(@Valid @RequestBody TopicDocumentRelationSaveDto dto) {
        return ApiResponse.ok(knowledgeManageService.saveTopicDocumentRelation(dto));
    }

    @Operation(summary = "移除主题文档关联")
    @PostMapping("/topic/document/remove")
    @RequiresPermission("kb:write")
    public ApiResponse<Boolean> removeTopicDocumentRelation(@Valid @RequestBody TopicDocumentRelationRemoveDto dto) {
        return ApiResponse.ok(knowledgeManageService.removeTopicDocumentRelation(dto));
    }

    @Operation(summary = "分页查询知识路由追踪")
    @PostMapping("/route/trace/page/query")
    @RequiresPermission("observe:read")
    public ApiResponse<KnowledgeRouteTracePageVo> queryRouteTracePage(@RequestBody(required = false) KnowledgeRouteTraceQueryDto dto) {
        return ApiResponse.ok(knowledgeManageService.queryRouteTracePage(dto == null ? new KnowledgeRouteTraceQueryDto() : dto));
    }
}
