package org.smartledge.ai.manage.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.smartledge.ai.auth.support.RequiresPermission;
import org.smartledge.ai.chatagent.dto.ConversationExchangeDetailQueryDto;
import org.smartledge.ai.chatagent.dto.ConversationIdentityDto;
import org.smartledge.ai.chatagent.dto.ConversationSessionListQueryDto;
import org.smartledge.ai.chatagent.dto.RetrievalObserveQueryDto;
import org.smartledge.ai.chatagent.model.ChannelExecutionView;
import org.smartledge.ai.chatagent.model.ConversationExchangeDetailView;
import org.smartledge.ai.chatagent.model.ConversationMemorySummaryView;
import org.smartledge.ai.chatagent.model.ConversationSessionView;
import org.smartledge.ai.chatagent.model.RetrievalResultView;
import org.smartledge.ai.chatagent.model.StageBenchmarkView;
import org.smartledge.ai.chatagent.service.BusinessChatService;
import org.smartledge.ai.chatagent.vo.ConversationSessionListVo;
import org.smartledge.ai.manage.dto.QualityOverviewQueryDto;
import org.smartledge.ai.manage.service.QualityOverviewService;
import org.smartledge.ai.manage.vo.QualityOverviewVo;
import org.smartledge.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端观测：租户内会话与知识运行全景。会话读写仍走用户端；这里只读本租户会话。
 */
@RestController
@RequestMapping("/manage/observability")
@RequiresPermission("observe:read")
public class ObservabilityManageController {

    private final QualityOverviewService qualityOverviewService;
    private final BusinessChatService businessChatService;

    public ObservabilityManageController(QualityOverviewService qualityOverviewService,
                                         BusinessChatService businessChatService) {
        this.qualityOverviewService = qualityOverviewService;
        this.businessChatService = businessChatService;
    }

    @Operation(summary = "查询知识运行全景")
    @PostMapping("/quality/overview/query")
    public ApiResponse<QualityOverviewVo> queryQualityOverview(
        @RequestBody(required = false) QualityOverviewQueryDto dto) {
        return ApiResponse.ok(qualityOverviewService.queryQualityOverview(
            dto == null ? new QualityOverviewQueryDto() : dto));
    }

    @Operation(summary = "分页查询本租户会话")
    @PostMapping("/session/page/query")
    public ApiResponse<ConversationSessionListVo> querySessions(
        @RequestBody(required = false) ConversationSessionListQueryDto dto) {
        return ApiResponse.ok(businessChatService.listObservableSessions(dto));
    }

    @Operation(summary = "查询本租户会话详情")
    @PostMapping("/session/detail/query")
    public ApiResponse<ConversationSessionView> querySession(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.getObservableSession(dto.getConversationId()));
    }

    @Operation(summary = "查询本租户轮次详情")
    @PostMapping("/exchange/detail/query")
    public ApiResponse<ConversationExchangeDetailView> queryExchange(
        @Valid @RequestBody ConversationExchangeDetailQueryDto dto) {
        return ApiResponse.ok(businessChatService.getObservableExchangeDetail(dto.getConversationId(), dto.getExchangeId()));
    }

    @Operation(summary = "查询本租户轮次检索结果")
    @PostMapping("/exchange/retrieval/results")
    public ApiResponse<List<RetrievalResultView>> retrievalResults(@Valid @RequestBody RetrievalObserveQueryDto dto) {
        return ApiResponse.ok(businessChatService.getObservableRetrievalResults(
            dto.getConversationId(), Long.parseLong(dto.getExchangeId())));
    }

    @Operation(summary = "查询本租户轮次通道执行")
    @PostMapping("/exchange/channel/executions")
    public ApiResponse<List<ChannelExecutionView>> channelExecutions(@Valid @RequestBody RetrievalObserveQueryDto dto) {
        return ApiResponse.ok(businessChatService.getObservableChannelExecutions(
            dto.getConversationId(), Long.parseLong(dto.getExchangeId())));
    }

    @Operation(summary = "重建本租户会话长期摘要")
    @PostMapping("/session/summary/rebuild")
    public ApiResponse<ConversationMemorySummaryView> rebuildSummary(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.rebuildObservableConversationSummary(dto.getConversationId()));
    }

    @Operation(summary = "查询阶段基准")
    @PostMapping("/stage/benchmarks")
    public ApiResponse<List<StageBenchmarkView>> stageBenchmarks() {
        return ApiResponse.ok(businessChatService.getStageBenchmarks());
    }
}
