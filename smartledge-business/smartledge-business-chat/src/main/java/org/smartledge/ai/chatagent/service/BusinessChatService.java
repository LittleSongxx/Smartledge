package org.smartledge.ai.chatagent.service;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.rag.runtime.config.ChatAgentProperties;
import org.smartledge.ai.rag.runtime.agent.AgentState;
import org.smartledge.ai.rag.runtime.model.ChatMessage;
import org.smartledge.ai.auth.support.DemoChatQuota;
import org.smartledge.ai.auth.support.PortfolioPermissions;
import org.smartledge.ai.chatagent.dto.ChatRequestDto;
import org.smartledge.ai.chatagent.dto.ConversationSessionListQueryDto;
import org.smartledge.ai.chatagent.model.ConversationExchangeDetailView;
import org.smartledge.ai.chatagent.model.ConversationExchangeView;
import org.smartledge.ai.chatagent.model.KnowledgeDocumentOptionView;
import org.smartledge.ai.chatagent.model.ConversationMemorySummaryView;
import org.smartledge.ai.chatagent.model.ConversationSessionView;
import org.smartledge.ai.chatagent.model.ChannelExecutionView;
import org.smartledge.ai.chatagent.model.RetrievalResultView;
import org.smartledge.ai.chatagent.model.StageBenchmarkView;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.chatagent.rag.executor.ConversationExecutor;
import org.smartledge.ai.chatagent.rag.executor.ConversationExecutorRegistry;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.PromptRenderedSourceEvidence;
import org.smartledge.ai.chatagent.rag.model.RagPromptAssemblyResult;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.service.ExplicitCitationBindingResult;
import org.smartledge.ai.chatagent.rag.service.ExplicitCitationBindingService;
import org.smartledge.ai.chatagent.support.CodeProvenanceResolver;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.rag.runtime.port.DocumentEvidencePort;
import org.smartledge.ai.chatagent.rag.service.ChatPreparationOrchestrator;
import org.smartledge.ai.chatagent.support.SinkEmitHelper;
import org.smartledge.ai.chatagent.support.StreamEventMetadata;
import org.smartledge.ai.chatagent.support.StreamEventWriter;
import org.smartledge.ai.chatagent.vo.ConversationResetVo;
import org.smartledge.ai.chatagent.vo.ConversationSessionListVo;
import org.smartledge.ai.chatagent.vo.ConversationStopVo;
import org.smartledge.ai.prompt.PromptTemplateNames;
import org.smartledge.ai.prompt.PromptTemplateService;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.BaseCode;
import org.smartledge.enums.ChatTurnStatus;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.smartledge.exception.SuperAgentFrameException;
import org.smartledge.lease.RedisLeaseManager;
import org.smartledge.ai.manage.service.DocumentAclStore;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.ai.rag.runtime.port.KnowledgeScopePort;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseOption;
import org.smartledge.ai.rag.runtime.port.KnowledgeBaseCatalogPort;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @description: 服务层
 * @author: Song
 **/

@Slf4j
@AllArgsConstructor
@Service
public class BusinessChatService {

    private static final ZoneId CHAT_ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final String SOURCE_SNAPSHOT_VISIBILITY = "GENERATION_VISIBLE";
    private static final Duration CHAT_RUNNING_LEASE_TTL = Duration.ofSeconds(30);
    private static final Duration CHAT_RUNNING_LEASE_RENEW_INTERVAL = Duration.ofSeconds(10);
    /** 部分答案落库节流间隔：崩溃最多丢这段窗口内的增量，终态不受影响。 */
    private static final long PARTIAL_ANSWER_FLUSH_INTERVAL_MILLIS = 2_000L;

    private final ChatCheckpointManager checkpointManager;
    private final ChatAgentProperties chatAgentProperties;
    private final ConversationArchiveStore conversationArchiveStore;
    private final ChatRuntimeRegistry chatRuntimeRegistry;
    private final RecommendationService recommendationService;
    private final StreamEventWriter streamEventWriter;
    private final RedisLeaseManager redisLeaseManager;
    private final ChatPreparationOrchestrator chatPreparationOrchestrator;
    private final ConversationExecutorRegistry conversationExecutorRegistry;
    private final ConversationMemoryService conversationMemoryService;
    private final DocumentEvidencePort documentKnowledgeService;
    private final ConversationTraceStageStore conversationTraceStageStore;
    private final RetrievalObserveStore retrievalObserveStore;
    private final StageBenchmarkService stageBenchmarkService;
    private final PromptTemplateService promptTemplateService;
    private final ExplicitCitationBindingService explicitCitationBindingService;
    private final KnowledgeScopePort knowledgeBaseRetrievalScopeService;
    private final KnowledgeBaseCatalogPort knowledgeBaseCatalogPort;
    private final ConversationAccessGuard conversationAccessGuard;
    private final ConversationIdentityService conversationIdentityService;
    private final DocumentAclStore documentAclStore;
    private final org.smartledge.ai.manage.service.KnowledgeManageService knowledgeManageService;
    private final CodeProvenanceResolver codeProvenanceResolver;
    private final DemoChatQuota demoChatQuota;
    private final org.smartledge.ai.chatagent.support.ChatRateLimiter chatRateLimiter;
    private final ChatExchangeFeedbackService chatExchangeFeedbackService;

    public Flux<String> openConversationStream(ChatRequestDto request) {

        return Flux.defer(() -> openDeferredConversationStream(request));
    }

    private Flux<String> openDeferredConversationStream(ChatRequestDto request) {

        log.info("======request内容：{}", JSON.toJSONString(request));
        StreamLaunchPlan launchPlan = null;
        RequestIdentity rateIdentity = null;
        boolean leaseClaimed = false;
        boolean concurrencyAcquired = false;
        try {

            launchPlan = buildLaunchPlan(request);

            rateIdentity = conversationAccessGuard.requireIdentity();
            if (!chatRateLimiter.tryAcquireUserWindow(rateIdentity.tenantId(), rateIdentity.userId())) {
                return rejectionFlux("提问过于频繁，请稍后再试", launchPlan.getConversationId(), null);
            }
            concurrencyAcquired = chatRateLimiter.tryAcquireTenantConversation(
                rateIdentity.tenantId(), launchPlan.getConversationId());
            if (!concurrencyAcquired) {
                return rejectionFlux("当前组织的并发问答已达上限，请稍后再试", launchPlan.getConversationId(), null);
            }

            leaseClaimed = claimConversationLease(launchPlan);
            if (!leaseClaimed) {
                return rejectionFlux("该会话当前正在执行中，请稍后再试", launchPlan.getConversationId(), null);
            }

            BootstrapResult bootstrapResult = bootstrapConversation(launchPlan);
            if (StrUtil.isNotBlank(bootstrapResult.getRejectionMessage())) {
                releaseTenantConcurrencyQuietly(rateIdentity.tenantId(), launchPlan.getConversationId());
                return rejectionFlux(bootstrapResult.getRejectionMessage(), launchPlan.getConversationId(), null);
            }
            return bootstrapResult.getOutbound();
        }
        catch (RuntimeException exception) {
            log.error("会话启动失败, conversationId={}, question={}",
                launchPlan == null ? "" : launchPlan.getConversationId(),
                request.getQuestion(),
                exception);
            if (concurrencyAcquired && rateIdentity != null && launchPlan != null) {
                releaseTenantConcurrencyQuietly(rateIdentity.tenantId(), launchPlan.getConversationId());
            }
            if (leaseClaimed && launchPlan != null) {
                releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
            }
            return rejectionFlux(
                buildErrorMessage(exception),
                launchPlan == null ? null : launchPlan.getConversationId(),
                null
            );
        }
    }

    private BootstrapResult bootstrapConversation(StreamLaunchPlan launchPlan) {

        ConversationExchangeView exchangeView = null;
        try {

            exchangeView = conversationArchiveStore.startExchange(
                launchPlan.getConversationId(),
                conversationAccessGuard.requireCurrentUserId(),
                launchPlan.getQuestion(),
                launchPlan.getChatMode(),
                launchPlan.getSelectedDocumentId(),
                launchPlan.getSelectedDocumentName(),
                launchPlan.getKnowledgeBaseSelectionSnapshot()
            );

            TaskInfo taskInfo = createTaskInfo(launchPlan, exchangeView);

            if (!chatRuntimeRegistry.register(taskInfo)) {

                failBootstrappedExchange(launchPlan.getConversationId(), exchangeView.getExchangeId(), "该会话当前正在执行中，请稍后再试");

                releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
                return BootstrapResult.rejected("该会话当前正在执行中，请稍后再试");
            }

            return BootstrapResult.ready(bindClientChannel(taskInfo));
        }
        catch (RuntimeException exception) {

            releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
            if (exchangeView != null) {

                failBootstrappedExchange(launchPlan.getConversationId(), exchangeView.getExchangeId(), buildErrorMessage(exception));
            }
            return BootstrapResult.rejected(buildErrorMessage(exception));
        }
    }

    private TaskInfo createTaskInfo(StreamLaunchPlan launchPlan, ConversationExchangeView exchangeView) {

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 会话执行跨 Reactor boundedElastic、chat-rag-executor 线程池与模型流式回调，
        // 请求线程的 ThreadLocal 上下文在这些线程上都不存在。因此在请求线程上把本轮租户
        // 固化进 TaskInfo，后续所有异步阶段都以它为准。
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("对话入口缺少租户上下文，拒绝启动会话执行");
        }

        List<String> thinkingSteps = Collections.synchronizedList(new ArrayList<>());
        List<SearchReference> references = Collections.synchronizedList(new ArrayList<>());
        Set<String> usedTools = ConcurrentHashMap.newKeySet();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        ConversationTraceRecorder traceRecorder = new ConversationTraceRecorder(
            conversationTraceStageStore,
            retrievalObserveStore,
            launchPlan.getConversationId(),
            exchangeView.getExchangeId(),
            traceId,
            tenantId
        );
        StreamEventMetadata eventMetadata = new StreamEventMetadata(
            launchPlan.getConversationId(),
            exchangeView.getExchangeId()
        );

        ChatDebugTrace debugTrace = initializeDebugTrace(null);

        return new TaskInfo(
            launchPlan.getConversationId(),
            exchangeView.getExchangeId(),
            launchPlan.getQuestion(),
            launchPlan.getChatMode(),
            traceId,
            tenantId,
            conversationAccessGuard.requireCurrentUserId(),
            launchPlan.getSelectedDocumentId(),
            launchPlan.getSelectedDocumentName(),
            launchPlan.getSelectedTaskId(),
            launchPlan.getKnowledgeBaseSelectionSnapshot(),
            launchPlan.getCurrentDate(),
            launchPlan.getCurrentDateText(),
            null,
            debugTrace,
            traceRecorder,
            sink,
            eventMetadata,
            launchPlan.getLeaseKey(),
            launchPlan.getLeaseOwnerToken(),
            thinkingSteps,
            references,
            usedTools,
            System.currentTimeMillis()
        );
    }

    private Flux<String> bindClientChannel(TaskInfo taskInfo) {

        return taskInfo.sink().asFlux()

            .doOnSubscribe(ignored -> activateGeneration(taskInfo))

            .doOnCancel(() -> TenantContext.runWith(taskInfo.tenantId(), () -> stopTask(taskInfo, "客户端已取消请求")));
    }

    private void activateGeneration(TaskInfo taskInfo) {
        try {
            if (taskInfo.finalized().get()) {
                return;
            }

            Disposable leaseRenewalDisposable = startLeaseRenewal(taskInfo);
            taskInfo.setLeaseRenewalDisposable(leaseRenewalDisposable);
            if (taskInfo.finalized().get() && !leaseRenewalDisposable.isDisposed()) {
                leaseRenewalDisposable.dispose();
                return;
            }

            Disposable disposable = buildConversationExecution(taskInfo).subscribe();

            taskInfo.setDisposable(disposable);
            if (taskInfo.finalized().get() && !disposable.isDisposed()) {
                disposable.dispose();
            }
        }
        catch (RuntimeException exception) {

            finishWithFailure(taskInfo, exception);
        }
    }

    private Flux<String> buildConversationExecution(TaskInfo taskInfo) {
        return Flux.defer(() -> {

                safeEmit(taskInfo.sink(), streamEventWriter.thinking("正在分析问题上下文。", taskInfo.eventMetadata()));
                // 计划准备会读写业务表（会话记忆、归档、知识范围），而它在 boundedElastic 上执行。
                return Mono.fromCallable(() -> TenantContext.callWith(taskInfo.tenantId(), () -> prepareExecutionPlan(taskInfo)))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(plan -> {

                        ConversationExecutor executor = conversationExecutorRegistry.get(plan.getMode());
                        return executor.execute(taskInfo);
                    });
            })
            .publishOn(Schedulers.boundedElastic())

            .doOnNext(chunk -> emitModelChunk(taskInfo, chunk))
            .doOnError(error -> TenantContext.runWith(taskInfo.tenantId(), () -> finishWithFailure(taskInfo, error)))
            .doOnComplete(() -> TenantContext.runWith(taskInfo.tenantId(), () -> finishSuccessfully(taskInfo)));
    }

    private StreamLaunchPlan buildLaunchPlan(ChatRequestDto request) {

        String question = normalizeQuestion(request.getQuestion());

        RequestIdentity identity = conversationAccessGuard.requireIdentity();
        String conversationId = conversationIdentityService.resolveForLaunch(request.getConversationId(), identity);
        conversationAccessGuard.requireOwnedOrNew(conversationId);
        ChatQueryMode chatMode = parseRequiredChatMode(request.getChatMode());
        if (PortfolioPermissions.isDemo(identity)) {
            if (chatMode == ChatQueryMode.OPEN_CHAT) {
                throw new SuperAgentFrameException(
                    BaseCode.PARAMETER_ERROR.getCode(),
                    PortfolioPermissions.OPEN_CHAT_BLOCKED_MESSAGE
                );
            }
            if (question.length() > PortfolioPermissions.MAX_QUESTION_CHARS) {
                throw new SuperAgentFrameException(
                    BaseCode.PARAMETER_ERROR.getCode(),
                    PortfolioPermissions.QUESTION_TOO_LONG_MESSAGE
                );
            }
            demoChatQuota.consumeOrThrow(identity.userId());
        }
        KnowledgeBaseSelectionMode selectionMode = parseKnowledgeBaseSelectionMode(request.getKnowledgeBaseSelectionMode());
        validateChatModeAndKnowledgeBaseSelection(chatMode, selectionMode);

        KnowledgeBaseSelectionSnapshot knowledgeBaseSelection = knowledgeBaseRetrievalScopeService.resolve(
            chatMode,
            selectionMode,
            request.getSelectedKnowledgeBaseIds()
        );
        KnowledgeDocumentDescriptor selectedDocument = resolveSelectedDocument(chatMode, request.getSelectedDocumentId(), knowledgeBaseSelection);

        LocalDate currentDate = LocalDate.now(CHAT_ZONE_ID);
        String currentDateText = formatCurrentDate(currentDate);
        return new StreamLaunchPlan(
            question,
            conversationId,
            chatMode,
            selectedDocument == null ? null : selectedDocument.getDocumentId(),
            selectedDocument == null ? "" : selectedDocument.getDocumentName(),
            selectedDocument == null ? null : selectedDocument.getLastIndexTaskId(),
            knowledgeBaseSelection,

            ConversationRuntimeKeys.leaseKey(identity.tenantId(), conversationId),

            UUID.randomUUID().toString(),
            currentDate,
            currentDateText
        );
    }

    private boolean claimConversationLease(StreamLaunchPlan launchPlan) {

        return redisLeaseManager.acquire(
            launchPlan.getLeaseKey(),
            launchPlan.getLeaseOwnerToken(),
            CHAT_RUNNING_LEASE_TTL
        );
    }

    private void releaseTenantConcurrencyQuietly(Long tenantId, String conversationId) {
        try {
            chatRateLimiter.releaseTenantConversation(tenantId, conversationId);
        }
        catch (RuntimeException exception) {
            log.warn("释放租户并发额度失败, conversationId={}", conversationId, exception);
        }
    }

    private void failBootstrappedExchange(String conversationId, long exchangeId, String errorMessage) {

        conversationArchiveStore.completeExchange(
            conversationId,
            exchangeId,
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            ChatTurnStatus.FAILED,
            errorMessage,
            null,
            null
        );
    }

    private Flux<String> rejectionFlux(String message) {
        return rejectionFlux(message, null, null);
    }

    private Flux<String> rejectionFlux(String message, String conversationId, Long exchangeId) {

        return Flux.just(streamEventWriter.error(message, new StreamEventMetadata(conversationId, exchangeId)));
    }

    public ConversationStopVo stopConversation(String conversationId) {
        return stopConversation(conversationId, "用户已停止生成");
    }

    public ConversationStopVo stopConversation(String conversationId, String reason) {
        conversationAccessGuard.requireOwned(conversationId);
        Optional<TaskInfo> taskInfoOptional = chatRuntimeRegistry.get(
            conversationAccessGuard.requireIdentity().tenantId(), conversationId);
        if (taskInfoOptional.isEmpty()) {
            return new ConversationStopVo(conversationId, false, "没有找到正在执行的会话");
        }
        return stopTask(taskInfoOptional.get(), reason);
    }

    private ConversationStopVo stopTask(TaskInfo taskInfo, String reason) {

        if (!taskInfo.tryFinalize()) {
            return new ConversationStopVo(taskInfo.conversationId(), false, "会话已经结束");
        }

        Optional<TaskInfo> currentTask = chatRuntimeRegistry.get(taskInfo.tenantId(), taskInfo.conversationId());
        if (currentTask.isPresent() && currentTask.get() != taskInfo) {

            return new ConversationStopVo(taskInfo.conversationId(), false, "会话已由新的执行接管");
        }

        taskInfo.cancelAgentExecution();

        Disposable disposable = taskInfo.disposable();
        if (disposable != null && !disposable.isDisposed()) {

            disposable.dispose();
        }

        String responseMessage = "已停止会话生成";
        ConversationTraceRecorder.StageHandle finalizeStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode.FINALIZE,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在收尾停止中的会话。",
                null
            );
        try {
            safeEmit(taskInfo.sink(), streamEventWriter.status("⏹ " + reason, taskInfo.eventMetadata()));
        }
        catch (RuntimeException exception) {
            log.warn("发送停止事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            responseMessage = "会话已停止，停止事件发送失败";
        }
        finally {
            try {
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭停止中的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                refreshDebugTraceRuntimeStats(taskInfo);
                ExplicitCitationBindingResult binding = bindTerminalSourceSnapshot(taskInfo);
                List<SearchReference> sourceSnapshot = binding.retrievedSources();
                conversationArchiveStore.completeExchange(
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    taskInfo.answerBuffer().toString(),
                    snapshotStringList(taskInfo.thinkingSteps()),
                    sourceSnapshot,
                    List.of(),
                    snapshotUsedTools(taskInfo.usedTools()),
                    taskInfo.debugTrace(),
                    ChatTurnStatus.STOPPED,
                    reason,
                    toNullable(taskInfo.firstResponseTimeMs().get()),
                    System.currentTimeMillis() - taskInfo.startTime()
                );
                if (taskInfo.traceRecorder() != null) {
                    Map<String, Object> finalizeSnapshot = sourceSnapshotTrace(binding);
                    finalizeSnapshot.put("finalStatus", ChatTurnStatus.STOPPED.name());
                    finalizeSnapshot.put("reason", reason);
                    finalizeSnapshot.put("answerLength", taskInfo.answerBuffer().length());
                    taskInfo.traceRecorder().completeStage(
                        finalizeStage,
                        "会话已按停止状态收尾。",
                        finalizeSnapshot
                    );
                }
            }
            catch (RuntimeException exception) {
                log.error("停止会话落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
                responseMessage = "会话已停止，收尾落库失败";
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(finalizeStage, "停止态收尾失败。", exception.getMessage(), null);
                }
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
        return new ConversationStopVo(taskInfo.conversationId(), true, responseMessage);
    }

    public ConversationSessionView getSession(String conversationId) {
        return loadSession(conversationId, false);
    }

    public ConversationSessionView getObservableSession(String conversationId) {
        return loadSession(conversationId, true);
    }

    private ConversationSessionView loadSession(String conversationId, boolean tenantObserve) {
        if (tenantObserve) {
            conversationAccessGuard.requireVisibleInTenant(conversationId);
        }
        else {
            conversationAccessGuard.requireOwned(conversationId);
        }
        ConversationArchiveStore.ConversationArchiveRecord archiveRecord = conversationArchiveStore.getSessionRecord(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        return overlayRuntimeSnapshot(toSessionView(archiveRecord, true, true));
    }

    public ConversationExchangeDetailView getExchangeDetail(String conversationId, String exchangeId) {
        return getExchangeDetail(conversationId, exchangeId, false);
    }

    public ConversationExchangeDetailView getObservableExchangeDetail(String conversationId, String exchangeId) {
        return getExchangeDetail(conversationId, exchangeId, true);
    }

    private ConversationExchangeDetailView getExchangeDetail(String conversationId, String exchangeId, boolean tenantObserve) {
        long resolvedExchangeId = parseRequiredLong(exchangeId, "exchangeId");
        ConversationSessionView sessionView = loadSession(conversationId, tenantObserve);
        ConversationExchangeView exchangeView = sessionView.getExchanges().stream()
            .filter(item -> item != null && item.getExchangeId() == resolvedExchangeId)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("轮次不存在: " + exchangeId));
        return new ConversationExchangeDetailView(
            conversationId,
            exchangeView,
            conversationTraceStageStore.listStageViews(conversationId, resolvedExchangeId)
        );
    }

    public ConversationSessionListVo listSessions(ConversationSessionListQueryDto dto) {
        int pageNo = parsePositiveInt(dto == null ? null : dto.getPageNo(), 1);
        int pageSize = parsePositiveInt(dto == null ? null : dto.getPageSize(), 20);
        String keyword = normalizeOptionalText(dto == null ? null : dto.getKeyword());
        ChatQueryMode chatMode = parseOptionalChatMode(dto == null ? null : dto.getChatMode());
        ChatTurnStatus turnStatus = parseOptionalTurnStatus(dto == null ? null : dto.getTurnStatus());

        ConversationArchiveStore.ConversationArchivePage archivePage = conversationArchiveStore.listSessionRecordPage(
            pageNo,
            pageSize,
            keyword,
            chatMode,
            turnStatus,
            conversationAccessGuard.requireCurrentUserId()
        );
        return toSessionListVo(archivePage);
    }

    public ConversationSessionListVo listObservableSessions(ConversationSessionListQueryDto dto) {
        Long ownerUserId = conversationAccessGuard.observeOwnerFilter();
        int pageNo = parsePositiveInt(dto == null ? null : dto.getPageNo(), 1);
        int pageSize = parsePositiveInt(dto == null ? null : dto.getPageSize(), 20);
        String keyword = normalizeOptionalText(dto == null ? null : dto.getKeyword());
        ChatQueryMode chatMode = parseOptionalChatMode(dto == null ? null : dto.getChatMode());
        ChatTurnStatus turnStatus = parseOptionalTurnStatus(dto == null ? null : dto.getTurnStatus());

        ConversationArchiveStore.ConversationArchivePage archivePage = conversationArchiveStore.listSessionRecordPage(
            pageNo,
            pageSize,
            keyword,
            chatMode,
            turnStatus,
            ownerUserId
        );
        return toSessionListVo(archivePage);
    }

    private ConversationSessionListVo toSessionListVo(ConversationArchiveStore.ConversationArchivePage archivePage) {
        List<ConversationSessionView> sessions = archivePage.records()
            .stream()
            .map(record -> toSessionView(record, false, false))
            .toList();

        long totalPages = archivePage.totalSize() <= 0
            ? 0
            : (archivePage.totalSize() + archivePage.pageSize() - 1) / archivePage.pageSize();
        return new ConversationSessionListVo(
            archivePage.pageNo(),
            archivePage.pageSize(),
            archivePage.totalSize(),
            totalPages,
            sessions
        );
    }

    public List<KnowledgeDocumentOptionView> listKnowledgeDocumentOptions() {
        List<org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor> retrievable =
            documentKnowledgeService.listRetrievableDocuments();
        if (retrievable == null || retrievable.isEmpty()) {
            return List.of();
        }
        // 用户端的文档范围只出现"可检索 且 当前身份可见"的文档：范围选择器本身就是越权入口之一。
        java.util.Set<Long> visibleDocumentIds = documentAclStore.visibleDocumentIds(
            retrievable.stream()
                .map(org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor::getDocumentId)
                .toList(),
            conversationAccessGuard.requireIdentity());
        return retrievable.stream()
            .filter(document -> visibleDocumentIds.contains(document.getDocumentId()))
            .map(this::toKnowledgeDocumentOptionView)
            .toList();
    }

    /**
     * 用户端的知识库选项。
     *
     * <p>知识库可以出现在列表里，但**计数必须只统计当前身份可见的文档**：后端原先返回的是
     * 租户内可检索文档总数，等于把"存在但你看不到"的文档数量暴露给用户端。</p>
     */
    public List<KnowledgeBaseOption> listKnowledgeBaseOptions() {
        List<KnowledgeBaseOption> options = knowledgeBaseCatalogPort.listOptions();
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        RequestIdentity identity = conversationAccessGuard.requireIdentity();
        List<Long> knowledgeBaseIds = options.stream()
            .map(option -> parseRequiredLong(option.getId(), "knowledgeBaseId"))
            .toList();
        List<KnowledgeDocumentDescriptor> retrievable =
            documentKnowledgeService.listRetrievableDocumentsByKnowledgeBaseIds(knowledgeBaseIds);
        if (retrievable == null || retrievable.isEmpty()) {
            return List.of();
        }
        Set<Long> visibleDocumentIds = documentAclStore.visibleDocumentIds(
            retrievable.stream().map(KnowledgeDocumentDescriptor::getDocumentId).toList(), identity);
        Map<Long, Long> visibleCountByKnowledgeBase = new java.util.LinkedHashMap<>();
        for (KnowledgeDocumentDescriptor document : retrievable) {
            if (document.getKnowledgeBaseId() == null || !visibleDocumentIds.contains(document.getDocumentId())) {
                continue;
            }
            visibleCountByKnowledgeBase.merge(document.getKnowledgeBaseId(), 1L, Long::sum);
        }
        return options.stream()
            .filter(option -> visibleCountByKnowledgeBase.getOrDefault(
                parseRequiredLong(option.getId(), "knowledgeBaseId"), 0L) > 0)
            .map(option -> withRetrievableCount(option,
                visibleCountByKnowledgeBase.getOrDefault(parseRequiredLong(option.getId(), "knowledgeBaseId"), 0L)))
            .toList();
    }

    private KnowledgeBaseOption withRetrievableCount(KnowledgeBaseOption option, Long count) {
        KnowledgeBaseOption projected = new KnowledgeBaseOption();
        projected.setId(option.getId());
        projected.setBaseName(option.getBaseName());
        projected.setDescription(option.getDescription());
        projected.setIsDefault(option.getIsDefault());
        projected.setRetrievableDocumentCount(String.valueOf(count == null ? 0L : count));
        return projected;
    }

    public ConversationMemorySummaryView rebuildConversationSummary(String conversationId) {
        // 记忆摘要是会话历史的压缩文本，归属校验与读取会话同级。
        conversationAccessGuard.requireOwned(conversationId);
        return conversationMemoryService.rebuildConversationSummary(conversationId);
    }

    public ConversationMemorySummaryView rebuildObservableConversationSummary(String conversationId) {
        conversationAccessGuard.requireVisibleInTenant(conversationId);
        return conversationMemoryService.rebuildConversationSummary(conversationId);
    }

    public ConversationResetVo resetConversation(String conversationId) {

        conversationAccessGuard.requireOwned(conversationId);
        ConversationStopVo stopResult = stopConversation(conversationId, "会话被重置");

        ConversationArchiveStore.ConversationRemovalResult removalResult = conversationArchiveStore.deleteSession(conversationId);

        conversationMemoryService.deleteConversationSummary(conversationId);
        conversationTraceStageStore.deleteStages(conversationId);
        retrievalObserveStore.deleteByConversation(conversationId);
        int removedCheckpointCount = checkpointManager.clear(conversationId);
        return new ConversationResetVo(
            conversationId,
            stopResult.isStopped(),
            removalResult.removedDialogueCount(),
            removalResult.removedExchangeCount(),
            removedCheckpointCount,
            "会话已重置"
        );
    }

    private void emitModelChunk(TaskInfo taskInfo, String chunk) {
        String flushCandidate = null;
        // Share the answer buffer lock with terminal snapshots. A late callback cannot change a finalized result.
        synchronized (taskInfo.answerBuffer()) {
            if (taskInfo.finalized().get()) { return; }
            taskInfo.answerBuffer().append(chunk);
            if (taskInfo.firstResponseTimeMs().get() == 0L) {
                taskInfo.firstResponseTimeMs().compareAndSet(0L, System.currentTimeMillis() - taskInfo.startTime());
            }
            safeEmit(taskInfo.sink(), streamEventWriter.text(chunk, taskInfo.eventMetadata()));
            flushCandidate = partialFlushSnapshot(taskInfo);
        }
        if (flushCandidate != null) {
            flushPartialAnswerQuietly(taskInfo, flushCandidate);
        }
    }

    /**
     * 崩溃安全兜底：流式生成中把已生成答案周期性落库（只覆盖 RUNNING 轮次），
     * 进程崩溃或断线后已生成部分不再只存在于内存。终态写入永远覆盖这些中间快照。
     */
    private String partialFlushSnapshot(TaskInfo taskInfo) {
        long now = System.currentTimeMillis();
        int length = taskInfo.answerBuffer().length();
        if (length <= taskInfo.getLastPartialFlushLength()) {
            return null;
        }
        if (now - taskInfo.getLastPartialFlushAtMillis() < PARTIAL_ANSWER_FLUSH_INTERVAL_MILLIS) {
            return null;
        }
        taskInfo.setLastPartialFlushAtMillis(now);
        taskInfo.setLastPartialFlushLength(length);
        return taskInfo.answerBuffer().toString();
    }

    private void flushPartialAnswerQuietly(TaskInfo taskInfo, String answer) {
        try {
            TenantContext.callWith(taskInfo.tenantId(),
                () -> conversationArchiveStore.flushPartialAnswer(taskInfo.conversationId(), taskInfo.exchangeId(), answer));
        }
        catch (RuntimeException exception) {
            // 兜底落库失败不影响流式回答；终态写入仍然完整收口。
            log.debug("部分答案落库失败, conversationId={}, exchangeId={}",
                taskInfo.conversationId(), taskInfo.exchangeId(), exception);
        }
    }

    private void finishSuccessfully(TaskInfo taskInfo) {
        if (!taskInfo.tryFinalize()) {
            return;
        }

        String answer = taskInfo.answerBuffer().toString();
        List<SearchReference> uniqueReferences = promptRenderedSourceReferences(taskInfo);
        ExplicitCitationBindingResult citationBinding = null;
        ConversationTraceRecorder.StageHandle finalizeStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode.FINALIZE,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在收尾已完成会话。",
                null
            );
        try {
            assertPromptRenderedSourcesMatchManifest(taskInfo, uniqueReferences);
            citationBinding = explicitCitationBindingService.bind(
                answer,
                taskInfo.promptAssemblyResult(),
                taskInfo.traceRecorder(),
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name()
            );
            assertRetrievedMatchesRenderedSources(taskInfo, citationBinding);
            uniqueReferences = citationBinding.retrievedSources();
        }
        catch (RuntimeException exception) {
            finishPostProcessFailure(taskInfo, finalizeStage, "显式引用绑定失败。", exception, answer);
            return;
        }
        ConversationTraceRecorder.StageHandle recommendationStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode.RECOMMENDATION,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在生成推荐追问。",
                null
            );
        List<String> recommendations;
        if (taskInfo.executionPlan() != null
            && taskInfo.executionPlan().getMode() == org.smartledge.ai.chatagent.rag.model.ExecutionMode.CLARIFICATION) {
            recommendations = taskInfo.executionPlan().getClarificationOptions() == null
                ? List.of()
                : new ArrayList<>(taskInfo.executionPlan().getClarificationOptions());
        }
        else {
            recommendations = recommendationService.generateRecommendations(
                taskInfo.question(),
                answer,
                historicalRecentExchanges(taskInfo),
                taskInfo.traceRecorder()
            );
        }
        if (taskInfo.traceRecorder() != null) {
            taskInfo.traceRecorder().completeStage(recommendationStage, "推荐追问生成完成。", Map.of(
                "recommendationCount", recommendations.size(),
                "recommendations", recommendations
            ));
        }

        try {
            if (!uniqueReferences.isEmpty()) {
                safeEmit(taskInfo.sink(), streamEventWriter.references(uniqueReferences, taskInfo.eventMetadata()));
            }
            if (!recommendations.isEmpty()) {
                safeEmit(taskInfo.sink(), streamEventWriter.recommendations(recommendations, taskInfo.eventMetadata()));
            }
        }
        catch (RuntimeException exception) {
            log.warn("补发引用或推荐事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
        }
        finally {
            try {
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭成功完成的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                refreshDebugTraceRuntimeStats(taskInfo);
                conversationArchiveStore.completeExchange(
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    answer,
                    snapshotStringList(taskInfo.thinkingSteps()),
                    uniqueReferences,
                    recommendations,
                    snapshotUsedTools(taskInfo.usedTools()),
                    taskInfo.debugTrace(),
                    ChatTurnStatus.COMPLETED,
                    "",
                    toNullable(taskInfo.firstResponseTimeMs().get()),
                    System.currentTimeMillis() - taskInfo.startTime()
                );
                if (taskInfo.traceRecorder() != null) {
                    Map<String, Object> finalizeSnapshot = citationBinding == null
                        ? sourceSnapshotTrace(uniqueReferences)
                        : sourceSnapshotTrace(citationBinding);
                    finalizeSnapshot.put("finalStatus", ChatTurnStatus.COMPLETED.name());
                    finalizeSnapshot.put("referenceCount", uniqueReferences.size());
                    finalizeSnapshot.put("explicitCitationCount", citationBinding == null
                        ? 0
                        : citationBinding.explicitCitations().size());
                    finalizeSnapshot.put("recommendationCount", recommendations.size());
                    finalizeSnapshot.put("answerLength", answer.length());
                    taskInfo.traceRecorder().completeStage(
                        finalizeStage,
                        "会话已按完成状态收尾。",
                        finalizeSnapshot
                    );
                }
            }
            catch (RuntimeException exception) {
                log.error("成功会话收尾落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(finalizeStage, "完成态收尾失败。", exception.getMessage(), null);
                }
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
    }

    private void finishPostProcessFailure(TaskInfo taskInfo,
                                          ConversationTraceRecorder.StageHandle finalizeStage,
                                          String stageMessage,
                                          RuntimeException error,
                                          String answer) {
        String errorMessage = buildErrorMessage(error);
        log.error("会话后处理失败, conversationId={}, exchangeId={}, error={}",
            taskInfo.conversationId(),
            taskInfo.exchangeId(),
            errorMessage,
            error);
        try {
            safeEmit(taskInfo.sink(), streamEventWriter.error(errorMessage, taskInfo.eventMetadata()));
        }
        catch (RuntimeException emitException) {
            log.warn("发送后处理失败事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), emitException);
        }
        finally {
            try {
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException completeException) {
                log.warn("关闭后处理失败的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), completeException);
            }
            try {
                refreshDebugTraceRuntimeStats(taskInfo);
                List<SearchReference> sourceSnapshot = List.of();
                conversationArchiveStore.completeExchange(
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    answer,
                    snapshotStringList(taskInfo.thinkingSteps()),
                    sourceSnapshot,
                    List.of(),
                    snapshotUsedTools(taskInfo.usedTools()),
                    taskInfo.debugTrace(),
                    ChatTurnStatus.FAILED,
                    errorMessage,
                    toNullable(taskInfo.firstResponseTimeMs().get()),
                    System.currentTimeMillis() - taskInfo.startTime()
                );
                if (taskInfo.traceRecorder() != null) {
                    Map<String, Object> finalizeSnapshot = sourceSnapshotTrace(sourceSnapshot);
                    finalizeSnapshot.put("finalStatus", ChatTurnStatus.FAILED.name());
                    finalizeSnapshot.put("answerLength", answer == null ? 0 : answer.length());
                    taskInfo.traceRecorder().failStage(
                        finalizeStage,
                        stageMessage,
                        errorMessage,
                        finalizeSnapshot
                    );
                }
            }
            catch (RuntimeException archiveException) {
                log.error("后处理失败会话落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), archiveException);
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(finalizeStage, "后处理失败态收尾失败。", archiveException.getMessage(), null);
                }
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
    }

    private void finishWithFailure(TaskInfo taskInfo, Throwable error) {
        if (!taskInfo.tryFinalize()) {
            return;
        }

        String errorMessage = buildErrorMessage(error);
        ConversationTraceRecorder.StageHandle finalizeStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode.FINALIZE,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在收尾失败会话。",
                null
            );

        log.error("会话执行失败, conversationId={}, exchangeId={}, error={}",
            taskInfo.conversationId(),
            taskInfo.exchangeId(),
            errorMessage,
            error);

        try {
            safeEmit(taskInfo.sink(), streamEventWriter.error(errorMessage, taskInfo.eventMetadata()));
        }
        catch (RuntimeException exception) {
            log.warn("发送失败事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
        }
        finally {
            try {
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭失败中的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                refreshDebugTraceRuntimeStats(taskInfo);
                ExplicitCitationBindingResult binding = bindTerminalSourceSnapshot(taskInfo);
                List<SearchReference> sourceSnapshot = binding.retrievedSources();
                conversationArchiveStore.completeExchange(
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    taskInfo.answerBuffer().toString(),
                    snapshotStringList(taskInfo.thinkingSteps()),
                    sourceSnapshot,
                    List.of(),
                    snapshotUsedTools(taskInfo.usedTools()),
                    taskInfo.debugTrace(),
                    ChatTurnStatus.FAILED,
                    errorMessage,
                    toNullable(taskInfo.firstResponseTimeMs().get()),
                    System.currentTimeMillis() - taskInfo.startTime()
                );
                if (taskInfo.traceRecorder() != null) {
                    Map<String, Object> finalizeSnapshot = sourceSnapshotTrace(binding);
                    finalizeSnapshot.put("finalStatus", ChatTurnStatus.FAILED.name());
                    finalizeSnapshot.put("errorMessage", errorMessage);
                    finalizeSnapshot.put("answerLength", taskInfo.answerBuffer().length());
                    taskInfo.traceRecorder().completeStage(
                        finalizeStage,
                        "会话已按失败状态收尾。",
                        finalizeSnapshot
                    );
                }
            }
            catch (RuntimeException exception) {
                log.error("失败会话收尾落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(finalizeStage, "失败态收尾失败。", exception.getMessage(), null);
                }
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
    }

    private String buildErrorMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private void refreshDebugTraceRuntimeStats(TaskInfo taskInfo) {
        if (taskInfo == null || taskInfo.debugTrace() == null || taskInfo.traceRecorder() == null) {
            return;
        }
        taskInfo.debugTrace().setModelUsageTraces(taskInfo.traceRecorder().snapshotModelUsageTraces());
        org.smartledge.ai.chatagent.model.debug.ChatLimitStats limitStats = taskInfo.traceRecorder().limitStats();
        limitStats.setModelCallsUsed(taskInfo.traceRecorder().snapshotModelUsageTraces().size());
        limitStats.setModelCallsRunLimit(chatAgentProperties.getMaxModelCallsPerRun());
        limitStats.setModelCallsThreadLimit(chatAgentProperties.getMaxModelCallsPerThread());
        limitStats.setToolCallsUsed(snapshotUsedTools(taskInfo.usedTools()).size());
        limitStats.setToolCallsRunLimit(chatAgentProperties.getMaxToolCallsPerRun());
        limitStats.setToolCallsThreadLimit(chatAgentProperties.getMaxToolCallsPerThread());
        taskInfo.debugTrace().setLimitStats(limitStats);
    }

    private void cleanup(TaskInfo taskInfo) {
        taskInfo.cancelAgentExecution();

        Disposable disposable = taskInfo.disposable();
        Disposable leaseRenewalDisposable = taskInfo.leaseRenewalDisposable();

        if (leaseRenewalDisposable != null && !leaseRenewalDisposable.isDisposed()) {

            leaseRenewalDisposable.dispose();
        }

        if (disposable != null && !disposable.isDisposed()) {

            disposable.dispose();
        }

        releaseLeaseQuietly(taskInfo.leaseKey(), taskInfo.leaseOwnerToken());

        try {
            chatRateLimiter.releaseTenantConversation(taskInfo.tenantId(), taskInfo.conversationId());
        }
        catch (RuntimeException exception) {
            log.warn("清理租户并发额度失败, conversationId={}", taskInfo.conversationId(), exception);
        }

        chatRuntimeRegistry.remove(taskInfo.tenantId(), taskInfo.conversationId(), taskInfo);
    }

    private List<SearchReference> deduplicateReferences(List<SearchReference> references) {
        Map<String, SearchReference> unique = new LinkedHashMap<>();

        for (SearchReference reference : references) {
            if (reference == null) {
                continue;
            }
            unique.putIfAbsent(reference.uniqueKey(), reference);
        }
        return new ArrayList<>(unique.values());
    }

    private List<SearchReference> promptRenderedSourceReferences(TaskInfo taskInfo) {
        RagPromptAssemblyResult promptAssemblyResult = taskInfo.promptAssemblyResult();
        List<SearchReference> candidates = promptAssemblyResult == null
            ? snapshotReferenceList(taskInfo.references())
            : promptAssemblyResult.getRenderedSourceEvidence().stream()
                .map(PromptRenderedSourceEvidence::reference)
                .toList();
        List<SearchReference> sourceSnapshot = deduplicateReferences(candidates);
        sourceSnapshot.forEach(reference -> reference.setGenerationVisible(true));
        return sourceSnapshot;
    }

    private ExplicitCitationBindingResult bindTerminalSourceSnapshot(TaskInfo taskInfo) {
        return explicitCitationBindingService.bind(
            taskInfo.answerBuffer().toString(),
            taskInfo.promptAssemblyResult(),
            taskInfo.traceRecorder(),
            taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null
                ? ""
                : taskInfo.executionPlan().getMode().name()
        );
    }

    private void assertPromptRenderedSourcesMatchManifest(TaskInfo taskInfo,
                                                          List<SearchReference> renderedSources) {
        RagPromptAssemblyResult promptAssemblyResult = taskInfo.promptAssemblyResult();
        if (promptAssemblyResult == null) {
            return;
        }
        List<String> actualIdentities = sourceSnapshotIdentities(renderedSources);
        if (!actualIdentities.equals(promptAssemblyResult.getRenderedSourceIdentities())) {
            throw new IllegalStateException(
                "Prompt rendered source evidence must equal manifest rendered source identities"
            );
        }
    }

    private void assertRetrievedMatchesRenderedSources(TaskInfo taskInfo,
                                                       ExplicitCitationBindingResult binding) {
        RagPromptAssemblyResult promptAssemblyResult = taskInfo.promptAssemblyResult();
        if (promptAssemblyResult == null) {
            return;
        }
        if (!binding.retrievedSourceIdentities().equals(promptAssemblyResult.getRenderedSourceIdentities())) {
            throw new IllegalStateException(
                "retrieved sources must equal Prompt rendered source identities"
            );
        }
        Set<String> retrievedIdentities = Set.copyOf(binding.retrievedSourceIdentities());
        List<String> unexpectedExplicit = binding.explicitCitationIdentities().stream()
            .filter(identity -> !retrievedIdentities.contains(identity))
            .toList();
        if (!unexpectedExplicit.isEmpty()) {
            throw new IllegalStateException(
                "explicit citations contain identities outside retrieved sources: " + unexpectedExplicit
            );
        }
        if (!binding.sourceSnapshotIdentities().equals(binding.explicitCitationIdentities())) {
            throw new IllegalStateException("sourceSnapshotIdentities must equal explicitCitationIdentities");
        }
    }

    private Map<String, Object> sourceSnapshotTrace(ExplicitCitationBindingResult binding) {
        Map<String, Object> snapshot = binding.finalizeIdentitySnapshot();
        snapshot.put("sourceSnapshotVisibility", SOURCE_SNAPSHOT_VISIBILITY);
        return snapshot;
    }

    private Map<String, Object> sourceSnapshotTrace(List<SearchReference> sourceSnapshot) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceSnapshotVisibility", SOURCE_SNAPSHOT_VISIBILITY);
        snapshot.put("sourceSnapshotReferenceCount", sourceSnapshot == null ? 0 : sourceSnapshot.size());
        snapshot.put("sourceSnapshotIdentities", sourceSnapshotIdentities(sourceSnapshot));
        snapshot.put("retrievedSourceIdentities", List.of());
        snapshot.put("explicitCitationIdentities", List.of());
        snapshot.put("retrievedSourceReferenceCount", 0);
        return snapshot;
    }

    private List<String> sourceSnapshotIdentities(List<SearchReference> sourceSnapshot) {
        return (sourceSnapshot == null ? List.<SearchReference>of() : sourceSnapshot).stream()
            .filter(Objects::nonNull)
            .map(SearchReference::uniqueKey)
            .toList();
    }

    private ChatDebugTrace initializeDebugTrace(ConversationExecutionPlan executionPlan) {
        if (executionPlan == null) {
            return ChatDebugTrace.builder()
                .codeCommit(codeProvenanceResolver.currentIdentity())
                .retrievalNotes(Collections.synchronizedList(new ArrayList<>()))
                .usedChannels(Collections.synchronizedList(new ArrayList<>()))
                .build();
        }
        return ChatDebugTrace.builder()
            .codeCommit(codeProvenanceResolver.currentIdentity())

            .executionMode(executionPlan.getMode() == null ? "" : executionPlan.getMode().name())
            .chatMode(executionPlan.getChatMode())

            .originalQuestion(executionPlan.getOriginalQuestion())
            .rewriteQuestion(executionPlan.getRewriteQuestion())
            .rewriteSubQuestions(executionPlan.getRewriteSubQuestions() == null ? List.of() : new ArrayList<>(executionPlan.getRewriteSubQuestions()))
            .retrievalQuestion(retrievalQuestion(executionPlan))
            .agentQuestion(executionPlan.getAgentQuestion())
            .navigationDecision(executionPlan.getNavigationDecision())

            .historySummary(executionPlan.getHistorySummary())
            .longTermSummary(executionPlan.getLongTermSummary())
            .recentHistoryTranscript(executionPlan.getRecentHistoryTranscript())
            .answerRecentTranscript(executionPlan.getAnswerRecentTranscript())
            .answerHistoryContext(executionPlan.getAnswerHistoryContext() == null
                ? ""
                : executionPlan.getAnswerHistoryContext().getRenderedText())
            .answerHistoryFollowUpQuestion(executionPlan.getAnswerHistoryContext() != null
                && executionPlan.getAnswerHistoryContext().isFollowUpQuestion())
            .historyCompressionApplied(executionPlan.isHistoryCompressionApplied())
            .historyCoveredExchangeId(executionPlan.getHistoryCoveredExchangeId())
            .historyCoveredExchangeCount(executionPlan.getHistoryCoveredExchangeCount())
            .historyCompressionCount(executionPlan.getHistoryCompressionCount())
            .currentDateText(executionPlan.getCurrentDateText())
            .requiresFreshSearch(executionPlan.isRequiresFreshSearch())
            .requiresCurrentDateAnchoring(executionPlan.isRequiresCurrentDateAnchoring())

            .retrievalSubQuestions(new ArrayList<>(retrievalSubQuestions(executionPlan)))
            .selectedDocumentId(selectedDocumentId(executionPlan))
            .selectedTaskId(selectedTaskId(executionPlan))

            .retrievalNotes(Collections.synchronizedList(new ArrayList<>()))
            .usedChannels(Collections.synchronizedList(new ArrayList<>()))
            .toolTraces(Collections.synchronizedList(new ArrayList<>()))
            .noEvidenceReply(executionPlan.getNoEvidenceReply())
            .build();
    }

    private ConversationExecutionPlan prepareExecutionPlan(TaskInfo taskInfo) {

        ConversationExecutionPlan executionPlan = chatPreparationOrchestrator.prepare(taskInfo);

        executionPlan.setAgentQuestion(buildAgentQuestion(executionPlan));
        Long selectedDocumentId = selectedDocumentId(executionPlan);
        String selectedDocumentName = selectedDocumentName(executionPlan);
        Long selectedTaskId = selectedTaskId(executionPlan);
        if (selectedDocumentId != null && !Objects.equals(selectedDocumentId, taskInfo.selectedDocumentId())) {
            conversationArchiveStore.refreshSessionScope(
                taskInfo.conversationId(),
                executionPlan.getChatMode(),
                selectedDocumentId,
                selectedDocumentName,
                taskInfo.knowledgeBaseSelectionSnapshot()
            );
        }
        taskInfo.setExecutionPlan(executionPlan);
        taskInfo.setDebugTrace(initializeDebugTrace(executionPlan));
        return executionPlan;
    }

    private String retrievalQuestion(ConversationExecutionPlan executionPlan) {
        RetrievalPlan retrievalPlan = retrievalPlan(executionPlan);
        return retrievalPlan == null ? "" : retrievalPlan.normalizedQuery();
    }

    private List<String> retrievalSubQuestions(ConversationExecutionPlan executionPlan) {
        RetrievalPlan retrievalPlan = retrievalPlan(executionPlan);
        return retrievalPlan == null ? List.of() : retrievalPlan.executionQueryTexts();
    }

    private Long selectedDocumentId(ConversationExecutionPlan executionPlan) {
        RetrievalPlan retrievalPlan = retrievalPlan(executionPlan);
        return retrievalPlan == null ? null : retrievalPlan.explicitlyAuthorizedDocumentId();
    }

    private String selectedDocumentName(ConversationExecutionPlan executionPlan) {
        RetrievalPlan retrievalPlan = retrievalPlan(executionPlan);
        return retrievalPlan == null ? "" : retrievalPlan.explicitlyAuthorizedDocumentName();
    }

    private Long selectedTaskId(ConversationExecutionPlan executionPlan) {
        RetrievalPlan retrievalPlan = retrievalPlan(executionPlan);
        return retrievalPlan == null ? null : retrievalPlan.explicitlyAuthorizedTaskId();
    }

    private RetrievalPlan retrievalPlan(ConversationExecutionPlan executionPlan) {
        return executionPlan == null ? null : executionPlan.getRetrievalPlan();
    }

    private ConversationSessionView toSessionView(ConversationArchiveStore.ConversationArchiveRecord archiveRecord,
                                                  boolean includeMemorySummary,
                                                  boolean includeExchanges) {

        var state = checkpointManager.get(archiveRecord.conversationId());
        List<ChatMessage> messageList = state.map(AgentState::messages).orElseGet(List::of);
        List<ConversationExchangeView> archiveExchanges = archiveRecord.exchanges() == null ? List.of() : archiveRecord.exchanges();
        List<ConversationExchangeView> exchanges = includeExchanges ? archiveExchanges : List.of();
        enrichFeedback(exchanges);
        int businessMessageCount = businessMessageCount(archiveExchanges);
        String businessLatestUserMessage = latestExchangeQuestion(archiveExchanges);
        String businessLatestAssistantMessage = latestExchangeAnswer(archiveExchanges);
        ConversationExchangeView latestExchange = latestExchange(archiveExchanges);

        return new ConversationSessionView(
            archiveRecord.conversationId(),
            archiveRecord.running(),

            state.map(value -> Math.toIntExact(value.checkpointCount())).orElse(0),

            businessMessageCount > 0 ? businessMessageCount : messageList.size(),
            StrUtil.isNotBlank(businessLatestUserMessage) ? businessLatestUserMessage : latestMessage(messageList, "user"),
            StrUtil.isNotBlank(businessLatestAssistantMessage) ? businessLatestAssistantMessage : latestMessage(messageList, "assistant"),
            latestExchange == null ? null : latestExchange.getExchangeId(),
            latestExchange == null || latestExchange.getStatus() == null ? "" : latestExchange.getStatus().name(),
            latestExchange == null || latestExchange.getErrorMessage() == null ? "" : latestExchange.getErrorMessage(),
            archiveRecord.chatMode(),
            archiveRecord.selectedDocumentId() == null ? "" : String.valueOf(archiveRecord.selectedDocumentId()),
            archiveRecord.selectedDocumentName(),
            archiveRecord.knowledgeBaseSelectionMode(),
            archiveRecord.selectedKnowledgeBaseIds(),
            archiveRecord.selectedKnowledgeBaseNames(),
            archiveRecord.createdAt(),
            archiveRecord.updatedAt(),
            exchanges,
            includeMemorySummary ? conversationMemoryService.getConversationSummary(archiveRecord.conversationId()) : null
        );
    }

    /** 会话视图补充用户反馈：观测与本人会话都取该轮最新一条反馈（只有归属用户能提交）。 */
    private void enrichFeedback(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return;
        }
        try {
            List<Long> exchangeIds = exchanges.stream()
                .filter(java.util.Objects::nonNull)
                .map(ConversationExchangeView::getExchangeId)
                .toList();
            Map<Long, ChatExchangeFeedbackService.FeedbackSummary> feedbacks =
                chatExchangeFeedbackService.latestByExchange(exchangeIds);
            if (feedbacks.isEmpty()) {
                return;
            }
            for (ConversationExchangeView exchange : exchanges) {
                ChatExchangeFeedbackService.FeedbackSummary summary = exchange == null
                    ? null
                    : feedbacks.get(exchange.getExchangeId());
                if (summary != null) {
                    exchange.setFeedbackRating(summary.rating());
                    exchange.setFeedbackComment(summary.comment());
                }
            }
        }
        catch (RuntimeException exception) {
            // 反馈是附加信号，读取失败不阻断会话视图。
            log.debug("补充轮次用户反馈失败, conversationId 未知", exception);
        }
    }

    private ConversationSessionView overlayRuntimeSnapshot(ConversationSessionView sessionView) {
        if (sessionView == null || sessionView.getExchanges() == null || sessionView.getExchanges().isEmpty()) {
            return sessionView;
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            return sessionView;
        }
        Optional<TaskInfo> runtimeOptional = chatRuntimeRegistry.get(tenantId, sessionView.getConversationId());
        if (runtimeOptional.isEmpty()) {
            return sessionView;
        }
        TaskInfo taskInfo = runtimeOptional.get();
        List<ConversationExchangeView> exchanges = new ArrayList<>(sessionView.getExchanges().size());
        boolean replaced = false;
        for (ConversationExchangeView exchange : sessionView.getExchanges()) {
            if (exchange == null) {
                continue;
            }
            if (exchange.getExchangeId() == taskInfo.exchangeId()) {
                exchanges.add(mergeRuntimeExchange(exchange, taskInfo));
                replaced = true;
                continue;
            }
            exchanges.add(exchange);
        }
        if (!replaced) {
            return sessionView;
        }
        sessionView.setExchanges(exchanges);
        sessionView.setMessageCount(businessMessageCount(exchanges));
        sessionView.setRunning(true);
        sessionView.setUpdatedAt(Instant.now());
        sessionView.setLatestExchangeId(taskInfo.exchangeId());
        sessionView.setLatestTurnStatus(ChatTurnStatus.RUNNING.name());
        String liveAnswer = taskInfo.answerBuffer().toString();
        if (StrUtil.isNotBlank(liveAnswer)) {
            sessionView.setLatestAssistantMessage(liveAnswer);
        }
        return sessionView;
    }

    private ConversationExchangeView mergeRuntimeExchange(ConversationExchangeView exchange,
                                                          TaskInfo taskInfo) {
        return new ConversationExchangeView(
            exchange.getExchangeId(),
            exchange.getQuestion(),
            taskInfo.answerBuffer().toString(),
            snapshotStringList(taskInfo.thinkingSteps()),
            List.of(),
            exchange.getRecommendations() == null ? List.of() : exchange.getRecommendations(),
            snapshotUsedTools(taskInfo.usedTools()),
            taskInfo.debugTrace(),
            ChatTurnStatus.RUNNING,
            exchange.getErrorMessage(),
            toNullable(taskInfo.firstResponseTimeMs().get()),
            System.currentTimeMillis() - taskInfo.startTime(),
            exchange.getKnowledgeBaseSelectionMode(),
            exchange.getSelectedKnowledgeBaseIds(),
            exchange.getSelectedKnowledgeBaseNames(),
            exchange.getRetrievalConfigSnapshotJson(),
            exchange.getCreateTime(),
            exchange.getEditTime(),
            exchange.getFeedbackRating(),
            exchange.getFeedbackComment()
        );
    }

    private KnowledgeDocumentDescriptor resolveSelectedDocument(ChatQueryMode chatMode,
                                                                String selectedDocumentId,
                                                                KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }
        String normalizedDocumentId = StrUtil.trimToNull(selectedDocumentId);
        if (chatMode == ChatQueryMode.OPEN_CHAT) {

            if (normalizedDocumentId != null) {
                throw new IllegalArgumentException("开放式提问模式下不能传 selectedDocumentId");
            }
            return null;
        }
        if (chatMode == ChatQueryMode.AUTO_DOCUMENT) {
            if (normalizedDocumentId != null) {
                throw new IllegalArgumentException("自动知识问答模式下不能传 selectedDocumentId");
            }
            return null;
        }

        if (normalizedDocumentId == null) {
            throw new IllegalArgumentException("当前文档问答模式下必须选择一个文档");
        }
        Long resolvedDocumentId;
        try {
            resolvedDocumentId = Long.parseLong(normalizedDocumentId);
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("选择的文档编号格式不正确，请在「提问文档」列表中重新选择。", exception);
        }
        List<KnowledgeDocumentDescriptor> searchableDocuments = knowledgeBaseSelection == null
            || knowledgeBaseSelection.getSelectionMode() == KnowledgeBaseSelectionMode.NONE
            ? List.of()
            : knowledgeBaseSelection.getAllowedDocuments();
        boolean visibleInScope = searchableDocuments.stream()
            .anyMatch(item -> Objects.equals(item.getDocumentId(), resolvedDocumentId));
        if (!visibleInScope) {
            // 面向用户的说明：不出现内部文档 ID，并指向可执行的下一步（B1-3 / S21-J）。
            // 内部 ID 只留在服务端日志里，供排障对账。
            log.warn("所选文档不在当前可提问范围内，documentId={}, allowedDocumentCount={}",
                resolvedDocumentId, searchableDocuments.size());
            throw new IllegalArgumentException(searchableDocuments.isEmpty()
                ? "当前账号还没有可提问的文档：回答只会引用你有权限查看的文档。请重新选择知识库，或联系管理员授予文档访问权限。"
                : "所选文档已不在你可提问的范围内（可能被移除、停用、索引失效，或权限已变更）。请刷新后从「提问文档」列表中重新选择。");
        }
        return searchableDocuments.stream()
            .filter(item -> Objects.equals(item.getDocumentId(), resolvedDocumentId))
            .findFirst()
            .orElseThrow();
    }

    private KnowledgeDocumentOptionView toKnowledgeDocumentOptionView(KnowledgeDocumentDescriptor descriptor) {
        return new KnowledgeDocumentOptionView(
            descriptor.getDocumentId() == null ? "" : String.valueOf(descriptor.getDocumentId()),
            descriptor.getDocumentName(),
            descriptor.getKnowledgeBaseId() == null ? "" : String.valueOf(descriptor.getKnowledgeBaseId()),
            descriptor.getKnowledgeBaseName()
        );
    }

    private Long parseRequiredLong(String value, String fieldName) {
        try {
            return Long.parseLong(value);
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " 非法: " + value, exception);
        }
    }

    private int parsePositiveInt(String value, int defaultValue) {
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("分页参数非法: " + value, exception);
        }
    }

    private String normalizeOptionalText(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private ChatQueryMode parseOptionalChatMode(String value) {
        if (StrUtil.isBlank(value) || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return ChatQueryMode.valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("chatMode 非法: " + value, exception);
        }
    }

    private ChatQueryMode parseRequiredChatMode(String value) {
        ChatQueryMode chatMode = parseOptionalChatMode(value);
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }
        return chatMode;
    }

    private KnowledgeBaseSelectionMode parseKnowledgeBaseSelectionMode(String value) {
        try {
            return KnowledgeBaseSelectionMode.fromName(value);
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("knowledgeBaseSelectionMode 非法: " + value, exception);
        }
    }

    private void validateChatModeAndKnowledgeBaseSelection(ChatQueryMode chatMode,
                                                           KnowledgeBaseSelectionMode selectionMode) {
        KnowledgeBaseSelectionMode mode = selectionMode == null ? KnowledgeBaseSelectionMode.NONE : selectionMode;
        if (chatMode == ChatQueryMode.OPEN_CHAT) {
            if (mode != KnowledgeBaseSelectionMode.NONE) {
                throw new IllegalArgumentException("开放式提问模式必须使用 NONE 知识库选择模式");
            }
            return;
        }
        if (chatMode == ChatQueryMode.AUTO_DOCUMENT && mode == KnowledgeBaseSelectionMode.NONE) {
            throw new IllegalArgumentException("自动知识问答模式必须选择知识库或使用全部知识库");
        }
        if (chatMode == ChatQueryMode.DOCUMENT && mode == KnowledgeBaseSelectionMode.NONE) {
            throw new IllegalArgumentException("当前文档问答模式必须选择知识库或使用全部知识库");
        }
    }

    private ChatTurnStatus parseOptionalTurnStatus(String value) {
        if (StrUtil.isBlank(value) || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return ChatTurnStatus.valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("turnStatus 非法: " + value, exception);
        }
    }

    private int businessMessageCount(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ConversationExchangeView exchange : exchanges) {
            if (exchange == null) {
                continue;
            }
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                count++;
            }
            if (StrUtil.isNotBlank(exchange.getAnswer())) {
                count++;
            }
        }
        return count;
    }

    private String latestExchangeQuestion(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "";
        }
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = exchanges.get(index);
            if (exchange != null && StrUtil.isNotBlank(exchange.getQuestion())) {
                return exchange.getQuestion();
            }
        }
        return "";
    }

    private String latestExchangeAnswer(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "";
        }
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = exchanges.get(index);
            if (exchange != null && StrUtil.isNotBlank(exchange.getAnswer())) {
                return exchange.getAnswer();
            }
        }
        return "";
    }

    private ConversationExchangeView latestExchange(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return null;
        }
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = exchanges.get(index);
            if (exchange != null) {
                return exchange;
            }
        }
        return null;
    }

    private String latestMessage(List<?> messages, String type) {

        for (int index = messages.size() - 1; index >= 0; index--) {
            Object candidate = messages.get(index);
            if (candidate instanceof ChatMessage message && message.role().equals(type)) {
                return message.content();
            }
        }
        return "";
    }

    private List<ConversationExchangeView> recentExchanges(String conversationId) {

        return conversationArchiveStore.listRecentExchanges(
            conversationId,
            Math.max(1, chatAgentProperties.getHistoryPreviewTurns())
        );
    }

    private List<ConversationExchangeView> historicalRecentExchanges(TaskInfo taskInfo) {
        return recentExchanges(taskInfo.conversationId()).stream()
            .filter(exchange -> exchange.getExchangeId() != taskInfo.exchangeId())
            .toList();
    }

    private Disposable startLeaseRenewal(TaskInfo taskInfo) {

        return Flux.interval(CHAT_RUNNING_LEASE_RENEW_INTERVAL, CHAT_RUNNING_LEASE_RENEW_INTERVAL)

            // 租约续期跑在 Reactor parallel 调度器上；续期失败会走停止收尾并落库，因此必须带租户。
            .subscribe(ignored -> TenantContext.runWith(taskInfo.tenantId(), () -> renewLeaseOrStop(taskInfo)), error ->
                log.warn("租约续期任务出现异常, conversationId={}, exchangeId={}",
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    error)
            );
    }

    private void renewLeaseOrStop(TaskInfo taskInfo) {

        boolean renewed = redisLeaseManager.renew(
            taskInfo.leaseKey(),
            taskInfo.leaseOwnerToken(),
            CHAT_RUNNING_LEASE_TTL
        );
        if (renewed) {
            try {
                chatRateLimiter.refreshTenantConversationTtl(taskInfo.tenantId(), taskInfo.conversationId());
            }
            catch (RuntimeException exception) {
                log.warn("刷新租户并发额度 TTL 失败, conversationId={}", taskInfo.conversationId(), exception);
            }

            return;
        }

        log.warn("会话租约续期失败，准备停止当前会话, conversationId={}, exchangeId={}",
            taskInfo.conversationId(),
            taskInfo.exchangeId());
        Disposable leaseRenewalDisposable = taskInfo.leaseRenewalDisposable();
        if (leaseRenewalDisposable != null && !leaseRenewalDisposable.isDisposed()) {
            leaseRenewalDisposable.dispose();
        }
        stopTask(taskInfo, "会话租约已失效，已停止生成");
    }

    private void releaseLeaseQuietly(String leaseKey, String leaseOwnerToken) {
        try {

            redisLeaseManager.release(leaseKey, leaseOwnerToken);
        }
        catch (RuntimeException exception) {

            log.warn("释放会话租约时出现异常, leaseKey={}", leaseKey, exception);
        }
    }

    private Long toNullable(long value) {

        return value > 0 ? value : null;
    }

    private String normalizeQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            throw new SuperAgentFrameException("question 不能为空");
        }

        return question.trim();
    }

    private String buildAgentQuestion(ConversationExecutionPlan executionPlan) {
        return promptTemplateService.render(PromptTemplateNames.AGENT_QUESTION, Map.of(
            "currentDateText", StrUtil.blankToDefault(executionPlan.getCurrentDateText(), ""),
            "requiresCurrentDateAnchoring", executionPlan.isRequiresCurrentDateAnchoring(),
            "requiresFreshSearch", executionPlan.isRequiresFreshSearch(),
            "hasHistorySummary", StrUtil.isNotBlank(executionPlan.getHistorySummary()),
            "historySummary", StrUtil.blankToDefault(executionPlan.getHistorySummary(), ""),
            "question", StrUtil.blankToDefault(executionPlan.getOriginalQuestion(), "")
        ));
    }

    private String formatCurrentDate(LocalDate currentDate) {

        return currentDate + "（" + chineseWeekday(currentDate.getDayOfWeek()) + "）";
    }

    private String chineseWeekday(DayOfWeek dayOfWeek) {

        return switch (dayOfWeek) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }

    private void safeEmit(Sinks.Many<String> sink, String payload) {

        SinkEmitHelper.emitNext(sink, payload);
    }

    private void safeComplete(Sinks.Many<String> sink) {

        SinkEmitHelper.emitComplete(sink);
    }

    private List<String> snapshotStringList(List<String> source) {
        synchronized (source) {
            return List.copyOf(source);
        }
    }

    private List<SearchReference> snapshotReferenceList(List<SearchReference> source) {
        synchronized (source) {
            return new ArrayList<>(source);
        }
    }

    private List<String> snapshotUsedTools(Set<String> source) {
        return new ArrayList<>(source);
    }

    public List<RetrievalResultView> getRetrievalResults(String conversationId, long exchangeId) {
        conversationAccessGuard.requireOwned(conversationId);
        return retrievalObserveStore.listResults(conversationId, exchangeId);
    }

    public List<RetrievalResultView> getObservableRetrievalResults(String conversationId, long exchangeId) {
        conversationAccessGuard.requireVisibleInTenant(conversationId);
        return retrievalObserveStore.listResults(conversationId, exchangeId);
    }

    public List<ChannelExecutionView> getChannelExecutions(String conversationId, long exchangeId) {
        conversationAccessGuard.requireOwned(conversationId);
        return retrievalObserveStore.listChannelExecutions(conversationId, exchangeId);
    }

    public List<ChannelExecutionView> getObservableChannelExecutions(String conversationId, long exchangeId) {
        conversationAccessGuard.requireVisibleInTenant(conversationId);
        return retrievalObserveStore.listChannelExecutions(conversationId, exchangeId);
    }

    /**
     * 用户端的"知识路由说明"数据（原实现直接调 {@code /manage/knowledge/route/trace/page/query}，
     * 非管理员会静默降级）。
     *
     * <p>用户端只允许读**自己的**会话：归属校验与读取会话详情同级，然后复用同一份路由追踪服务，
     * 因此不存在"用户端另有一套路由解释"的第二权威。</p>
     */
    public org.smartledge.ai.manage.vo.KnowledgeRouteTracePageVo getKnowledgeRouteTrace(String conversationId) {
        conversationAccessGuard.requireOwned(conversationId);
        org.smartledge.ai.manage.dto.KnowledgeRouteTraceQueryDto query =
            new org.smartledge.ai.manage.dto.KnowledgeRouteTraceQueryDto();
        query.setConversationId(conversationId);
        query.setPageNo("1");
        query.setPageSize("200");
        return knowledgeManageService.queryRouteTracePage(query);
    }

    public List<StageBenchmarkView> getStageBenchmarks() {
        return stageBenchmarkService.listAll();
    }

    private void safeRefreshConversationSummary(String conversationId) {
        try {
            conversationMemoryService.refreshConversationSummaryAsync(conversationId);
        }
        catch (RuntimeException exception) {
            log.warn("刷新会话摘要失败, conversationId={}", conversationId, exception);
        }
    }

}
