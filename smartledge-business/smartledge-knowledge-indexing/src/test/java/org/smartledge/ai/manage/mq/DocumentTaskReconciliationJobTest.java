package org.smartledge.ai.manage.mq;

import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.smartledge.ai.manage.mq.message.DocumentIndexBuildMessage;
import org.smartledge.ai.manage.service.DocumentTaskLogService;
import org.smartledge.ai.manage.support.MybatisLambdaCacheTestSupport;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账任务不变量测试。
 *
 * <p>对应缺陷 S19-B4 / S19-B7：旧实现在 {@code afterCommit} 之后同步发送，数据库已提交、
 * 消息未发出时崩溃就永久丢失触发；失败或停止路径留下的 RUNNING 任务又会让文档永久无法重建
 * （因为 buildIndex 拒绝存在活跃任务的文档，而全仓库没有定时对账任务）。</p>
 *
 * <p>对账任务按固定顺序查询：先补投待执行任务，再判定失联任务，最后释放文档状态。
 * 因此 mock 的连续返回值必须与这个顺序一致。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentTaskReconciliationJobTest {

    @Mock
    private SuperAgentDocumentTaskMapper taskMapper;

    @Mock
    private SuperAgentDocumentMapper documentMapper;

    @Mock
    private DocumentMessagePublisher messagePublisher;

    @Mock
    private DocumentTaskLogService taskLogService;

    private org.smartledge.ai.manage.support.DocumentTaskInFlightRegistry inFlightRegistry;

    private DocumentTaskReconciliationJob job;

    private DocumentMessagingProperties properties;

    @BeforeAll
    static void initMybatisMetadata() {
        MybatisLambdaCacheTestSupport.initialize(SuperAgentDocumentTask.class, SuperAgentDocument.class);
    }

    @BeforeEach
    void setUp() {
        properties = new DocumentMessagingProperties();
        DocumentMessagingProperties.Reconcile reconcile = new DocumentMessagingProperties.Reconcile();
        reconcile.setBatchSize(10);
        reconcile.setMaxRedispatchAttempts(3);
        reconcile.setDispatchGraceMillis(1000L);
        reconcile.setStaleTaskTimeoutMillis(1000L);
        properties.setReconcile(reconcile);
        inFlightRegistry = new org.smartledge.ai.manage.support.DocumentTaskInFlightRegistry();
        job = new DocumentTaskReconciliationJob(taskMapper, documentMapper, messagePublisher, taskLogService,
            properties, inFlightRegistry);
    }

    private SuperAgentDocumentTask indexBuildTask(Integer status, Integer retryCount) {
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(1001L);
        task.setDocumentId(2001L);
        task.setPlanId(3001L);
        task.setTaskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode());
        task.setTaskStatus(status);
        task.setRetryCount(retryCount);
        task.setCreateTime(new Date(System.currentTimeMillis() - 60_000L));
        task.setStartTime(new Date(System.currentTimeMillis() - 60_000L));
        return task;
    }

    /** 补投查询返回给定任务，失联查询与文档释放查询都返回空。 */
    private void stubRedispatchQuery(SuperAgentDocumentTask task) {
        when(taskMapper.selectList(any()))
            .thenReturn(List.of(task))
            .thenReturn(Collections.emptyList());
        when(documentMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    /** 补投查询返回空，失联查询返回给定任务。 */
    private void stubStaleQuery(SuperAgentDocumentTask task) {
        when(taskMapper.selectList(any()))
            .thenReturn(Collections.emptyList())
            .thenReturn(List.of(task));
        when(documentMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("未被执行的新任务会被补投，payload 携带 document/task/plan")
    void undeliveredNewTaskIsRedispatched() {
        stubRedispatchQuery(indexBuildTask(DocumentTaskStatusEnum.NEW.getCode(), 0));

        job.reconcile();

        ArgumentCaptor<DocumentIndexBuildMessage> captor = ArgumentCaptor.forClass(DocumentIndexBuildMessage.class);
        verify(messagePublisher, times(1)).publishIndexBuild(captor.capture());
        assertThat(captor.getValue().getDocumentId()).isEqualTo(2001L);
        assertThat(captor.getValue().getTaskId()).isEqualTo(1001L);
        assertThat(captor.getValue().getPlanId()).isEqualTo(3001L);
        verify(taskMapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("补投次数达到上限后判定失败，不再继续补投")
    void redispatchIsBoundedByAttemptLimit() {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(2001L);
        stubRedispatchQuery(indexBuildTask(DocumentTaskStatusEnum.NEW.getCode(), 3));
        when(documentMapper.selectById(2001L)).thenReturn(document);

        job.reconcile();

        verify(messagePublisher, never()).publishIndexBuild(any());
        verify(taskMapper, times(1)).update(any(), any());
        verify(taskLogService, times(1)).saveLog(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("在途任务（已消费、在执行器排队）不补投、不计数、不判失败")
    void inFlightNewTaskIsNotRedispatchedOrFailed() {
        SuperAgentDocumentTask task = indexBuildTask(DocumentTaskStatusEnum.NEW.getCode(), 3);
        stubRedispatchQuery(task);
        inFlightRegistry.markInFlight(1001L);

        job.reconcile();

        verify(messagePublisher, never()).publishIndexBuild(any());
        verify(taskMapper, never()).update(any(), any());
        verify(taskLogService, never()).saveLog(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("在途的 RUNNING 任务即使无 checkpoint 心跳也不判失联")
    void inFlightRunningTaskIsNotMarkedStale() {
        SuperAgentDocumentTask task = indexBuildTask(DocumentTaskStatusEnum.RUNNING.getCode(), 0);
        task.setStartTime(new Date(System.currentTimeMillis() - 3 * 3600_000L));
        task.setExtJson(null);
        stubStaleQuery(task);
        inFlightRegistry.markInFlight(1001L);

        job.reconcile();

        verify(taskMapper, never()).update(any(), any());
        verify(taskLogService, never()).saveLog(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(messagePublisher, never()).publishIndexBuild(any());
    }

    @Test
    @DisplayName("开工已久但 GraphRAG checkpoint 仍新鲜的任务不得判失联")
    void runningTaskWithFreshGraphRagCheckpointIsNotStale() {
        properties.getReconcile().setStaleTaskTimeoutMillis(120_000L);
        SuperAgentDocumentTask task = indexBuildTask(DocumentTaskStatusEnum.RUNNING.getCode(), 0);
        task.setStartTime(new Date(System.currentTimeMillis() - 3 * 3600_000L));
        task.setExtJson("{\"graphRagBuild\":{\"status\":\"RUNNING\",\"stage\":\"EXTRACTING\","
            + "\"lastCheckpointTime\":\""
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            + "\"}}");
        stubStaleQuery(task);

        job.reconcile();

        verify(taskMapper, never()).update(any(), any());
        verify(taskLogService, never()).saveLog(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(messagePublisher, never()).publishIndexBuild(any());
    }

    @Test
    @DisplayName("GraphRAG checkpoint 已超过静默窗口时仍判失联")
    void runningTaskWithStaleGraphRagCheckpointIsMarkedFailed() {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(2001L);
        SuperAgentDocumentTask task = indexBuildTask(DocumentTaskStatusEnum.RUNNING.getCode(), 0);
        task.setStartTime(new Date(System.currentTimeMillis() - 3 * 3600_000L));
        task.setExtJson("{\"graphRagBuild\":{\"status\":\"RUNNING\",\"stage\":\"EXTRACTING\","
            + "\"lastCheckpointTime\":\""
            + LocalDateTime.now().minusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            + "\"}}");
        stubStaleQuery(task);
        when(documentMapper.selectById(2001L)).thenReturn(document);

        job.reconcile();

        verify(taskMapper, times(1)).update(any(), any());
        verify(taskLogService, times(1)).saveLog(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(messagePublisher, never()).publishIndexBuild(any());
    }

    @Test
    @DisplayName("失联的执行中任务被标记失败并写入任务日志，不产生补投")
    void staleRunningTaskIsMarkedFailed() {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(2001L);
        stubStaleQuery(indexBuildTask(DocumentTaskStatusEnum.RUNNING.getCode(), 0));
        when(documentMapper.selectById(2001L)).thenReturn(document);

        job.reconcile();

        verify(taskMapper, times(1)).update(any(), any());
        verify(taskLogService, times(1)).saveLog(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(messagePublisher, never()).publishIndexBuild(any());
    }

    @Test
    @DisplayName("补投失败不抛出，留待下一轮对账")
    void redispatchFailureIsContained() {
        stubRedispatchQuery(indexBuildTask(DocumentTaskStatusEnum.NEW.getCode(), 0));
        doThrow(new IllegalStateException("broker down")).when(messagePublisher).publishIndexBuild(any());

        job.reconcile();

        verify(taskMapper, never()).update(any(), any());
    }
}
