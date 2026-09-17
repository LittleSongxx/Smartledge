package org.smartledge.ai.chatagent.rag.executor;

import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.service.TaskInfo;
import org.smartledge.ai.chatagent.support.StreamEventMetadata;
import org.smartledge.ai.chatagent.support.StreamEventWriter;
import org.smartledge.ai.prompt.PromptTemplateService;
import org.smartledge.ai.rag.runtime.agent.AgentState;
import org.smartledge.ai.rag.runtime.agent.AgentStatePort;
import org.smartledge.ai.rag.runtime.config.ChatAgentProperties;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.smartledge.ai.rag.runtime.model.ChatMessage;
import org.smartledge.ai.rag.runtime.model.ChatStreamEvent;
import org.smartledge.ai.rag.runtime.port.ChatModelPort;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.ChatQueryMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReactAgentExecutorTenantPropagationTest {

    @Test
    @DisplayName("开放提问在 boundedElastic 上 begin checkpoint 仍带本轮租户")
    void beginCheckpointRunsInsideTaskTenantOnScheduler() {
        AgentStatePort states = mock(AgentStatePort.class);
        AtomicReference<Long> observedTenant = new AtomicReference<>();
        AtomicReference<String> observedThread = new AtomicReference<>();
        when(states.begin(anyString(), anyLong())).thenAnswer(invocation -> {
            observedTenant.set(TenantContext.get());
            observedThread.set(Thread.currentThread().getName());
            return new AgentState(invocation.getArgument(0), invocation.getArgument(1), 1L, 0L, 0, 0, List.of());
        });
        when(states.save(any(), anyInt(), anyInt(), anyList(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatModelPort model = mock(ChatModelPort.class);
        when(model.stream(anyList(), anyList(), any(ChatCallOptions.class))).thenReturn(Flux.empty());

        PromptTemplateService prompts = mock(PromptTemplateService.class);
        when(prompts.render(anyString(), any())).thenReturn("system");

        ReactAgentExecutor executor = new ReactAgentExecutor(
            model,
            states,
            List.of(),
            new ChatAgentProperties(),
            new StreamEventWriter(new ObjectMapper()),
            prompts
        );

        TenantContext.set(11L);
        try {
            executor.execute(taskInfo(7L)).collectList().block();
        }
        catch (RuntimeException ignored) {
            // 模型流没有完成事件时执行器会收口失败；本测试只锁定 begin 的租户作用域。
        }
        finally {
            TenantContext.clear();
        }

        assertThat(observedThread.get()).startsWith("boundedElastic");
        assertThat(observedTenant.get()).isEqualTo(7L);
    }

    @Test
    @DisplayName("任务没有租户时 checkpoint 不得凭空获得调用线程租户")
    void beginDoesNotInheritCallerTenantWhenTaskHasNone() {
        AgentStatePort states = mock(AgentStatePort.class);
        AtomicReference<Boolean> observedPresence = new AtomicReference<>();
        when(states.begin(anyString(), anyLong())).thenAnswer(invocation -> {
            observedPresence.set(TenantContext.isPresent());
            return new AgentState(invocation.getArgument(0), invocation.getArgument(1), 1L, 0L, 0, 0, List.of());
        });
        when(states.save(any(), anyInt(), anyInt(), anyList(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatModelPort model = mock(ChatModelPort.class);
        when(model.stream(anyList(), anyList(), any(ChatCallOptions.class))).thenReturn(Flux.empty());
        PromptTemplateService prompts = mock(PromptTemplateService.class);
        when(prompts.render(anyString(), any())).thenReturn("system");

        ReactAgentExecutor executor = new ReactAgentExecutor(
            model,
            states,
            List.of(),
            new ChatAgentProperties(),
            new StreamEventWriter(new ObjectMapper()),
            prompts
        );

        TenantContext.set(11L);
        try {
            executor.execute(taskInfo(null)).collectList().block();
        }
        catch (RuntimeException ignored) {
            // 同上：只观察 begin 时的租户有无。
        }
        finally {
            TenantContext.clear();
        }

        assertThat(observedPresence.get()).isFalse();
    }

    private TaskInfo taskInfo(Long tenantId) {
        ConversationExecutionPlan plan = mock(ConversationExecutionPlan.class);
        when(plan.getAgentQuestion()).thenReturn("开放提问租户传播");
        ChatDebugTrace debugTrace = new ChatDebugTrace();
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        return new TaskInfo(
            "react-tenant-propagation",
            1L,
            "开放提问租户传播",
            ChatQueryMode.OPEN_CHAT,
            "trace-react-tenant",
            tenantId,
            null,
            "",
            null,
            null,
            LocalDate.now(),
            "",
            plan,
            debugTrace,
            null,
            sink,
            new StreamEventMetadata("react-tenant-propagation", 1L),
            "lease-key",
            "lease-owner",
            Collections.synchronizedList(new ArrayList<>()),
            Collections.synchronizedList(new ArrayList<>()),
            ConcurrentHashMap.newKeySet(),
            System.currentTimeMillis()
        );
    }
}
