package org.smartledge.ai.chatagent.service;

import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.smartledge.database.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 追踪记录的租户传播不变量测试。
 *
 * <p>对应缺陷 S21-L：阶段追踪的写入既发生在 Reactor 回调里（例如检索完成回调），
 * 也发生在模型流式回调线程上（答案完成/失败/取消）。这些回调执行时请求线程早已返回，
 * ThreadLocal 上下文不存在，落库会 fail closed。</p>
 *
 * <p>追踪记录器是本轮的固定消费者，因此它自己负责在写入时进入本轮任务的租户作用域，
 * 而不是要求每个回调各自包一层。</p>
 */
class ConversationTraceRecorderTenantPropagationTest {

    @Test
    @DisplayName("无上下文线程上的阶段开始写入仍然带本轮租户")
    void startStageCarriesTaskTenantOnCallbackThread() {
        ConversationTraceStageStore stageStore = mock(ConversationTraceStageStore.class);
        AtomicReference<Long> observedTenant = new AtomicReference<>();
        when(stageStore.startStage(anyString(), anyLong(), anyString(), any(), anyInt(), any(), anyString(), anyString(), any()))
            .thenAnswer(invocation -> {
                observedTenant.set(TenantContext.get());
                return 99L;
            });
        ConversationTraceRecorder recorder = new ConversationTraceRecorder(stageStore, null, "conv-1", 1L, "trace-1", 3L);

        TenantContext.clear();
        recorder.startStage(ConversationTraceStageCode.RAG_RETRIEVE, "RETRIEVAL", "开始检索。", null);

        assertThat(observedTenant.get()).isEqualTo(3L);
        assertThat(TenantContext.isPresent()).isFalse();
    }

    @Test
    @DisplayName("无上下文线程上的阶段失败写入仍然带本轮租户")
    void failStageCarriesTaskTenantOnCallbackThread() {
        ConversationTraceStageStore stageStore = mock(ConversationTraceStageStore.class);
        AtomicReference<Long> observedTenant = new AtomicReference<>();
        when(stageStore.startStage(anyString(), anyLong(), anyString(), any(), anyInt(), any(), anyString(), anyString(), any()))
            .thenReturn(11L);
        ConversationTraceRecorder recorder = new ConversationTraceRecorder(stageStore, null, "conv-1", 1L, "trace-1", 3L);
        ConversationTraceRecorder.StageHandle handle =
            recorder.startStage(ConversationTraceStageCode.ANSWER_GENERATE, "RETRIEVAL", "开始生成。", null);
        org.mockito.Mockito.doAnswer(invocation -> {
            observedTenant.set(TenantContext.get());
            return null;
        }).when(stageStore).finishStage(anyLong(), any(), anyString(), anyString(), any(), anyLong());

        TenantContext.clear();
        recorder.failStage(handle, "生成失败。", "boom", null);

        assertThat(observedTenant.get()).isEqualTo(3L);
        assertThat(TenantContext.isPresent()).isFalse();
    }
}
