package org.smartledge.ai.chatagent.rag.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.RagRetrievalContext;
import org.smartledge.ai.chatagent.rag.service.RagPromptAssemblyService;
import org.smartledge.ai.chatagent.rag.service.RagRetrievalEngine;
import org.smartledge.ai.chatagent.service.ObservedChatModelService;
import org.smartledge.ai.chatagent.service.TaskInfo;
import org.smartledge.ai.chatagent.support.StreamEventMetadata;
import org.smartledge.ai.chatagent.support.StreamEventWriter;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.ChatQueryMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识问答执行器的租户传播不变量测试。
 *
 * <p>对应缺陷 S21-L：{@code RagChatExecutor} 用
 * {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} 把检索整体挪到
 * boundedElastic 线程，而租户上下文是 ThreadLocal。开关打开后检索通道会在另一个线程上
 * 查询业务表，拿不到租户即 fail closed（实测第一个真实对话抛 {@code MyBatisSystemException}）。</p>
 *
 * <p>这里锁定的是：检索调用必须在异步线程上处于本轮任务的租户作用域。</p>
 */
class RagChatExecutorTenantPropagationTest {

    @Test
    @DisplayName("检索阶段在 boundedElastic 线程上仍带本轮租户")
    void retrieveRunsInsideTenantScopeOnSchedulerThread() {
        RagRetrievalEngine retrievalEngine = mock(RagRetrievalEngine.class);
        AtomicReference<Long> observedTenant = new AtomicReference<>();
        AtomicReference<String> observedThread = new AtomicReference<>();
        RagRetrievalContext emptyContext = emptyContext();
        when(retrievalEngine.retrieve(any(), any())).thenAnswer(invocation -> {
            observedTenant.set(TenantContext.get());
            observedThread.set(Thread.currentThread().getName());
            return emptyContext;
        });

        RagChatExecutor executor = new RagChatExecutor(
            retrievalEngine,
            mock(RagPromptAssemblyService.class),
            new StreamEventWriter(new ObjectMapper()),
            mock(ObservedChatModelService.class)
        );

        TenantContext.set(7L);
        try {
            executor.execute(taskInfo(7L)).collectList().block();
        }
        finally {
            TenantContext.clear();
        }

        assertThat(observedThread.get()).startsWith("boundedElastic");
        assertThat(observedTenant.get()).isEqualTo(7L);
    }

    @Test
    @DisplayName("任务没有租户时检索阶段不得凭空获得默认租户")
    void retrieveDoesNotInventTenantWhenTaskHasNone() {
        RagRetrievalEngine retrievalEngine = mock(RagRetrievalEngine.class);
        AtomicReference<Boolean> observedPresence = new AtomicReference<>();
        when(retrievalEngine.retrieve(any(), any())).thenAnswer(invocation -> {
            observedPresence.set(TenantContext.isPresent());
            return emptyContext();
        });

        RagChatExecutor executor = new RagChatExecutor(
            retrievalEngine,
            mock(RagPromptAssemblyService.class),
            new StreamEventWriter(new ObjectMapper()),
            mock(ObservedChatModelService.class)
        );

        TenantContext.set(7L);
        try {
            executor.execute(taskInfo(null)).collectList().block();
        }
        finally {
            TenantContext.clear();
        }

        // 调用线程有租户但任务本身没有：不得把调用线程的租户带进异步检索。
        assertThat(observedPresence.get()).isFalse();
    }

    private RagRetrievalContext emptyContext() {
        RagRetrievalContext context = new RagRetrievalContext();
        context.setUsedChannels(Collections.synchronizedList(new ArrayList<>()));
        context.setRetrievalNotes(Collections.synchronizedList(new ArrayList<>()));
        context.setExecutionRequests(List.of());
        return context;
    }

    private TaskInfo taskInfo(Long tenantId) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        return new TaskInfo(
            "s21-tenant-propagation",
            1L,
            "租户传播测试",
            ChatQueryMode.DOCUMENT,
            "trace-tenant-propagation",
            tenantId,
            null,
            "",
            null,
            null,
            LocalDate.now(),
            "",
            mock(ConversationExecutionPlan.class),
            mock(ChatDebugTrace.class),
            null,
            sink,
            new StreamEventMetadata("s21-tenant-propagation", 1L),
            "lease-key",
            "lease-owner",
            Collections.synchronizedList(new ArrayList<>()),
            Collections.synchronizedList(new ArrayList<SearchReference>()),
            ConcurrentHashMap.newKeySet(),
            System.currentTimeMillis()
        );
    }
}
