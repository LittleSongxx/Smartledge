package org.smartledge.ai.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.smartledge.ai.manage.mq.DocumentMessagingTopology;
import org.smartledge.ai.manage.service.DocumentTaskLogService;
import org.smartledge.ai.manage.support.MybatisLambdaCacheTestSupport;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信处理不变量测试。
 *
 * <p>对应缺陷 S19-B5：旧消费者把消费异常全部吞掉，任务永远停在待执行状态。
 * 死信处理必须把消息落成任务失败记录，并且必须按消息类型判定而不是按队列名判定——
 * 死信消息经死信交换机投递后，消费端看到的队列名固定是死信队列，无法还原原始业务类型。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentAsyncProcessServiceImplDeadLetterTest {

    @Mock
    private SuperAgentDocumentTaskMapper taskMapper;

    @Mock
    private SuperAgentDocumentMapper documentMapper;

    @Mock
    private DocumentTaskLogService taskLogService;

    @Mock
    private DocumentMessagingTopology messagingTopology;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Spy
    private org.smartledge.ai.manage.support.DocumentTaskInFlightRegistry taskInFlightRegistry =
        new org.smartledge.ai.manage.support.DocumentTaskInFlightRegistry();

    @InjectMocks
    private DocumentAsyncProcessServiceImpl service;

    @BeforeAll
    static void initMybatisMetadata() {
        MybatisLambdaCacheTestSupport.initialize(SuperAgentDocumentTask.class);
    }

    private SuperAgentDocumentTask task(Integer status) {
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(1001L);
        task.setDocumentId(2001L);
        task.setTaskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode());
        task.setTaskStatus(status);
        return task;
    }

    @Test
    @DisplayName("索引构建消息进入死信后任务被标记失败并写入任务日志")
    void indexBuildDeadLetterMarksTaskFailed() {
        lenient().when(messagingTopology.indexBuildRoutingKey()).thenReturn("document-index-build");
        when(taskMapper.selectById(1001L)).thenReturn(task(DocumentTaskStatusEnum.NEW.getCode()));

        service.handleDeadLetter("document-index-build",
            "{\"documentId\":2001,\"taskId\":1001,\"planId\":3001}");

        verify(taskMapper, times(1)).update(any(), any());
        verify(taskLogService, times(1)).saveLog(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("在途任务的死信副本不标记失败（任务仍在排队或执行中）")
    void deadLetterForInFlightTaskIsSkipped() {
        lenient().when(messagingTopology.indexBuildRoutingKey()).thenReturn("document-index-build");
        when(taskMapper.selectById(1001L)).thenReturn(task(DocumentTaskStatusEnum.NEW.getCode()));
        taskInFlightRegistry.markInFlight(1001L);

        service.handleDeadLetter("document-index-build",
            "{\"documentId\":2001,\"taskId\":1001,\"planId\":3001}");

        verify(taskMapper, never()).update(any(), any());
        verify(taskLogService, never()).saveLog(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("无法识别的消息类型只记录，不改任务状态")
    void unknownMessageTypeDoesNotTouchTask() {
        service.handleDeadLetter("unknown-type", "{\"documentId\":2001,\"taskId\":1001}");

        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("消息体不可解析时只记录，不改任务状态")
    void unparsablePayloadDoesNotTouchTask() {
        lenient().when(messagingTopology.indexBuildRoutingKey()).thenReturn("document-index-build");

        service.handleDeadLetter("document-index-build", "not-a-json-body");

        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("已结束的任务不因死信被重复标记")
    void finishedTaskIsNotMarkedAgain() {
        lenient().when(messagingTopology.parseRouteRoutingKey()).thenReturn("document-parse-route");
        when(taskMapper.selectById(1001L)).thenReturn(task(DocumentTaskStatusEnum.SUCCESS.getCode()));

        service.handleDeadLetter("document-parse-route", "{\"documentId\":2001,\"taskId\":1001}");

        verify(taskMapper, never()).update(any(), any());
        verify(taskLogService, never()).saveLog(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("死信处理在消息消费线程上自己声明系统上下文")
    void deadLetterDeclaresSystemContextBeforeTouchingMappers() {
        lenient().when(messagingTopology.indexBuildRoutingKey()).thenReturn("document-index-build");
        AtomicReference<Boolean> systemContextAtMapperCall = new AtomicReference<>();
        when(taskMapper.selectById(1001L)).thenAnswer(invocation -> {
            systemContextAtMapperCall.set(TenantContext.isSystem());
            return task(DocumentTaskStatusEnum.NEW.getCode());
        });

        TenantContext.clear();
        service.handleDeadLetter("document-index-build", "{\"documentId\":2001,\"taskId\":1001,\"planId\":3001}");

        assertThat(systemContextAtMapperCall.get()).isTrue();
        assertThat(TenantContext.isPresent()).isFalse();
    }

    @Test
    @DisplayName("消息消费线程上提交索引构建时，方法体自身声明系统上下文")
    void submitIndexBuildDeclaresSystemContextBeforeTouchingMappers() {
        // 对应缺陷 S21-M：原实现只在提交给线程池的内部任务上声明系统上下文，
        // 方法体开头的 taskMapper.selectById 仍跑在消费线程上，租户开关打开后直接失败并进入死信。
        AtomicReference<Boolean> systemContextAtMapperCall = new AtomicReference<>();
        when(taskMapper.selectById(1001L)).thenAnswer(invocation -> {
            systemContextAtMapperCall.set(TenantContext.isSystem());
            return task(DocumentTaskStatusEnum.NEW.getCode());
        });

        ExecutorService indexBuildExecutor = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "indexBuildExecutorService", indexBuildExecutor);

        TenantContext.clear();
        service.submitIndexBuild(2001L, 1001L, 3001L);

        assertThat(systemContextAtMapperCall.get()).isTrue();
        assertThat(TenantContext.isPresent()).isFalse();
        verify(indexBuildExecutor, times(1)).execute(any());
    }
}
