package org.smartledge.ai.chatagent.service;

import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.model.ChannelExecutionView;
import org.smartledge.ai.chatagent.model.RetrievalResultView;
import org.smartledge.ai.chatagent.rag.model.ObservationPersistence;
import org.smartledge.ai.chatagent.rag.model.ObservationPersistence.ErrorType;
import org.smartledge.ai.chatagent.model.debug.ChatLimitStats;
import org.smartledge.ai.rag.runtime.model.ChatModelUsageTrace;
import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageState;
import org.smartledge.database.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.JdbcUpdateAffectedIncorrectNumberOfRowsException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: 服务层
 * @author: Song
 **/

@Slf4j
public class ConversationTraceRecorder implements org.smartledge.ai.rag.runtime.port.ModelObservationSink {

    private final ConversationTraceStageStore traceStageStore;
    private final RetrievalObserveStore retrievalObserveStore;
    private final String conversationId;
    private final long exchangeId;
    private final String traceId;

    /**
     * 本轮执行所属租户。
     *
     * <p>追踪写入由 Reactor 回调与模型流式回调触发，执行时已经不在请求线程上，
     * ThreadLocal 租户上下文不存在。记录器是本轮固定的写入消费者，因此由它自己
     * 在写入前后进出租户作用域，而不是要求每个回调各包一层。</p>
     */
    private final Long tenantId;
    private final List<ChatModelUsageTrace> modelUsageTraces = Collections.synchronizedList(new ArrayList<>());
    private final ChatLimitStats limitStats = new ChatLimitStats();

    public ConversationTraceRecorder(ConversationTraceStageStore traceStageStore,
                                     RetrievalObserveStore retrievalObserveStore,
                                     String conversationId,
                                     long exchangeId,
                                     String traceId,
                                     Long tenantId) {
        this.traceStageStore = traceStageStore;
        this.retrievalObserveStore = retrievalObserveStore;
        this.conversationId = conversationId;
        this.exchangeId = exchangeId;
        this.traceId = traceId;
        this.tenantId = tenantId;
    }

    public String conversationId() {
        return conversationId;
    }

    public long exchangeId() {
        return exchangeId;
    }

    public String traceId() {
        return traceId;
    }

    public StageHandle startStage(ConversationTraceStageCode stageCode,
                                  String executionMode,
                                  String summaryText,
                                  Object snapshot) {
        long stageId = withTenant(() -> traceStageStore.startStage(
            conversationId,
            exchangeId,
            traceId,
            stageCode,
            1,
            null,
            executionMode,
            summaryText,
            snapshot
        ));
        return new StageHandle(stageId, System.currentTimeMillis(), stageCode);
    }

    public void completeStage(StageHandle stageHandle,
                              String summaryText,
                              Object snapshot) {
        if (stageHandle == null) {
            return;
        }
        withTenant(() -> traceStageStore.finishStage(
            stageHandle.stageId(),
            ConversationTraceStageState.COMPLETED,
            summaryText,
            "",
            snapshot,
            System.currentTimeMillis() - stageHandle.startTimeMs()
        ));
    }

    public void failStage(StageHandle stageHandle,
                          String summaryText,
                          String errorMessage,
                          Object snapshot) {
        if (stageHandle == null) {
            return;
        }
        withTenant(() -> traceStageStore.finishStage(
            stageHandle.stageId(),
            ConversationTraceStageState.FAILED,
            summaryText,
            errorMessage,
            snapshot,
            System.currentTimeMillis() - stageHandle.startTimeMs()
        ));
    }

    public void failStage(StageHandle stageHandle,
                          String summaryText,
                          Throwable throwable,
                          Object snapshot) {
        if (stageHandle == null) {
            return;
        }
        String errorMessage = throwable == null ? "" : throwable.getMessage();
        String stackTrace = throwable == null ? "" : getStackTraceAsString(throwable);

        Object enhancedSnapshot = snapshot;
        if (throwable != null && snapshot instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshotMap = new LinkedHashMap<>((Map<String, Object>) snapshot);
            snapshotMap.put("exceptionClass", throwable.getClass().getName());
            snapshotMap.put("stackTrace", stackTrace);
            enhancedSnapshot = snapshotMap;
        } else if (throwable != null) {
            enhancedSnapshot = Map.of(
                "exceptionClass", throwable.getClass().getName(),
                "errorMessage", errorMessage,
                "stackTrace", stackTrace
            );
        }

        Object resolvedSnapshot = enhancedSnapshot;
        withTenant(() -> traceStageStore.finishStage(
            stageHandle.stageId(),
            ConversationTraceStageState.FAILED,
            summaryText,
            errorMessage,
            resolvedSnapshot,
            System.currentTimeMillis() - stageHandle.startTimeMs()
        ));
    }

    private void withTenant(Runnable action) {
        TenantContext.runWith(tenantId, action);
    }

    private <T> T withTenant(java.util.function.Supplier<T> action) {
        return TenantContext.callWith(tenantId, action);
    }

    private String getStackTraceAsString(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    public void addModelUsageTrace(ChatModelUsageTrace trace) {
        if (trace != null) {
            modelUsageTraces.add(trace);
        }
    }

    public List<ChatModelUsageTrace> snapshotModelUsageTraces() {
        return new ArrayList<>(modelUsageTraces);
    }

    public ChatLimitStats limitStats() {
        return limitStats;
    }

    public ObservationPersistence recordRetrievalResults(List<RetrievalResultView> results) {
        List<RetrievalResultView> observations = results == null ? List.of() : results;
        int expectedCandidateCount = observations.size();
        if (retrievalObserveStore == null) {
            return ObservationPersistence.notAttempted(expectedCandidateCount, ErrorType.NO_RECORDER);
        }
        try {
            int persistedCandidateCount = withTenant(() -> retrievalObserveStore.batchSaveResults(
                conversationId,
                exchangeId,
                observations
            ));
            if (persistedCandidateCount != expectedCandidateCount) {
                log.warn("检索结果快照持久化行数不守恒, conversationId={}, exchangeId={}, expected={}, persisted={}",
                    conversationId, exchangeId, expectedCandidateCount, persistedCandidateCount);
                return ObservationPersistence.failed(
                    expectedCandidateCount,
                    persistedCandidateCount,
                    ErrorType.ROW_COUNT_MISMATCH
                );
            }
            return ObservationPersistence.success(expectedCandidateCount);
        } catch (RuntimeException exception) {
            log.warn("记录检索结果快照失败, conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
            return ObservationPersistence.failed(
                expectedCandidateCount,
                classifyObservationPersistenceError(exception)
            );
        }
    }

    private ErrorType classifyObservationPersistenceError(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof JdbcUpdateAffectedIncorrectNumberOfRowsException) {
                return ErrorType.ROW_COUNT_MISMATCH;
            }
            if (current instanceof DataIntegrityViolationException) {
                return ErrorType.DATA_INTEGRITY_VIOLATION;
            }
            if (current instanceof IllegalArgumentException) {
                return ErrorType.VALIDATION_ERROR;
            }
            current = current.getCause();
        }
        return ErrorType.PERSISTENCE_ERROR;
    }

    public void recordChannelExecutions(List<ChannelExecutionView> executions) {
        if (retrievalObserveStore == null || executions == null || executions.isEmpty()) {
            return;
        }
        try {
            withTenant(() -> retrievalObserveStore.batchSaveChannelExecutions(conversationId, exchangeId, executions));
        } catch (RuntimeException exception) {
            log.warn("记录通道执行详情失败, conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }

    public record StageHandle(long stageId, long startTimeMs, ConversationTraceStageCode stageCode) {
    }
}
