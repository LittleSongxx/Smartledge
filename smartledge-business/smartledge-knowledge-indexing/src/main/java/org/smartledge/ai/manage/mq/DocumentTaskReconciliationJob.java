package org.smartledge.ai.manage.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.smartledge.ai.manage.mq.message.DocumentIndexBuildMessage;
import org.smartledge.ai.manage.mq.message.DocumentParseRouteMessage;
import org.smartledge.ai.manage.service.DocumentTaskLogService;
import org.smartledge.enums.DocumentIndexStatusEnum;
import org.smartledge.enums.DocumentLogLevelEnum;
import org.smartledge.enums.DocumentOperatorTypeEnum;
import org.smartledge.enums.DocumentParseStatusEnum;
import org.smartledge.enums.DocumentTaskEventTypeEnum;
import org.smartledge.enums.DocumentTaskStageEnum;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.BusinessStatus;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 消息触发链路的对账任务。
 *
 * <p>这是「投递之后崩溃」与「任务失联」两类缺口的唯一兜底。数据库里的任务状态是权威，
 * 本任务只做两件事：把该执行但没被执行的任务重新投递；把不可能再完成的任务判定为失败，
 * 使文档重新可重建。没有本任务时，{@code REPAIR_REQUIRED} / {@code RUNNING} 的任务会让文档永久卡死。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentTaskReconciliationJob {

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentMapper documentMapper;

    private final DocumentMessagePublisher messagePublisher;

    private final DocumentTaskLogService taskLogService;

    private final DocumentMessagingProperties properties;

    @Scheduled(fixedDelayString = "${app.manage.messaging.reconcile.interval-millis:60000}",
        initialDelayString = "${app.manage.messaging.reconcile.interval-millis:60000}")
    public void reconcile() {

        // 对账任务跨租户扫描，必须显式声明系统上下文：
        // 租户拦截器 fail closed，不声明就无法构造查询。
        TenantContext.runAsSystem(this::doReconcile);
    }

    private void doReconcile() {

        DocumentMessagingProperties.Reconcile config = properties.getReconcile();
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return;
        }
        int redispatched = redispatchUndeliveredTasks(config);
        int failed = failStaleRunningTasks(config);
        int released = releaseDocumentsWithoutActiveTask(config);
        if (redispatched > 0 || failed > 0 || released > 0) {
            log.info("消息链路对账完成，补投={}, 判失联={}, 释放文档={}", redispatched, failed, released);
        }
    }

    /**
     * 补投「已创建但从未被执行」的任务。
     *
     * <p>覆盖 {@code afterCommit} 之后进程崩溃导致触发消息永久丢失的场景。
     * 补投次数超过上限后判定为投递失败，避免无限重投。</p>
     */
    private int redispatchUndeliveredTasks(DocumentMessagingProperties.Reconcile config) {

        Date createdBefore = new Date(System.currentTimeMillis() - config.getDispatchGraceMillis());
        List<SuperAgentDocumentTask> candidates = taskMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.NEW.getCode())
            .lt(SuperAgentDocumentTask::getCreateTime, createdBefore)
            .orderByAsc(SuperAgentDocumentTask::getCreateTime)
            .last("LIMIT " + Math.max(1, config.getBatchSize())));

        int count = 0;
        for (SuperAgentDocumentTask task : candidates) {
            if (redispatch(task, config)) {
                count++;
            }
        }
        return count;
    }

    private boolean redispatch(SuperAgentDocumentTask task, DocumentMessagingProperties.Reconcile config) {

        int attempts = task.getRetryCount() == null ? 0 : task.getRetryCount();
        if (attempts >= Math.max(1, config.getMaxRedispatchAttempts())) {
            log.error("任务补投次数已达上限，判定为投递失败，documentId={}, taskId={}, attempts={}",
                task.getDocumentId(), task.getId(), attempts);
            markTaskFailed(task, "MESSAGE_DISPATCH_EXHAUSTED",
                "触发消息多次补投仍未被执行，已判定为投递失败。");
            releaseDocument(task);
            return true;
        }
        try {
            if (Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())) {
                messagePublisher.publishParseRoute(new DocumentParseRouteMessage(task.getDocumentId(), task.getId()));
            }
            else if (Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.BUILD_INDEX.getCode())) {
                if (task.getPlanId() == null) {
                    log.error("索引构建任务缺少方案id，无法补投，documentId={}, taskId={}", task.getDocumentId(), task.getId());
                    markTaskFailed(task, "MESSAGE_DISPATCH_EXHAUSTED", "索引构建任务缺少有效方案，无法补投。");
                    releaseDocument(task);
                    return true;
                }
                messagePublisher.publishIndexBuild(new DocumentIndexBuildMessage(task.getDocumentId(), task.getId(),
                    task.getPlanId()));
            }
            else {
                return false;
            }
            taskMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentTask>()
                .eq(SuperAgentDocumentTask::getId, task.getId())
                .set(SuperAgentDocumentTask::getRetryCount, attempts + 1));
            log.warn("已补投未被执行的任务，documentId={}, taskId={}, taskType={}, attempts={}",
                task.getDocumentId(), task.getId(), task.getTaskType(), attempts + 1);
            return true;
        }
        catch (Exception exception) {
            log.error("补投任务失败，等待下一轮对账，documentId={}, taskId={}", task.getDocumentId(), task.getId(), exception);
            return false;
        }
    }

    /**
     * 判定失联的 RUNNING 任务。
     *
     * <p>没有本步骤时，停止或失败路径留下的 RUNNING 任务会让 {@code buildIndex} 永久拒绝重建。</p>
     */
    private int failStaleRunningTasks(DocumentMessagingProperties.Reconcile config) {

        Date startedBefore = new Date(System.currentTimeMillis() - config.getStaleTaskTimeoutMillis());
        List<SuperAgentDocumentTask> candidates = taskMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.RUNNING.getCode())
            .and(wrapper -> wrapper.lt(SuperAgentDocumentTask::getStartTime, startedBefore)
                .or()
                .isNull(SuperAgentDocumentTask::getStartTime)
                .lt(SuperAgentDocumentTask::getCreateTime, startedBefore))
            .orderByAsc(SuperAgentDocumentTask::getStartTime)
            .last("LIMIT " + Math.max(1, config.getBatchSize())));

        int count = 0;
        for (SuperAgentDocumentTask task : candidates) {
            log.error("任务长时间处于执行中，判定为失联并标记失败，documentId={}, taskId={}, startTime={}",
                task.getDocumentId(), task.getId(), task.getStartTime());
            markTaskFailed(task, "TASK_STALE_TIMEOUT", "任务长时间未结束，已判定为失联并标记失败。");
            releaseDocument(task);
            count++;
        }
        return count;
    }

    /**
     * 释放「文档状态停留在构建中/解析中，但已没有活跃任务」的文档。
     *
     * <p>索引投递失败会把任务标为 FAILED，但文档的 {@code index_status} 仍停留在 BUILDING。
     * 若不释放，使用者既看不到失败原因，也无法再次触发构建。</p>
     */
    private int releaseDocumentsWithoutActiveTask(DocumentMessagingProperties.Reconcile config) {

        Date idleBefore = new Date(System.currentTimeMillis() - config.getDispatchGraceMillis());
        List<SuperAgentDocument> candidates = documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .and(wrapper -> wrapper
                .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILDING.getCode())
                .or()
                .eq(SuperAgentDocument::getParseStatus, DocumentParseStatusEnum.PARSING.getCode()))
            .lt(SuperAgentDocument::getEditTime, idleBefore)
            .orderByAsc(SuperAgentDocument::getEditTime)
            .last("LIMIT " + Math.max(1, config.getBatchSize())));

        int count = 0;
        for (SuperAgentDocument document : candidates) {
            if (hasActiveTask(document.getId())) {
                continue;
            }
            if (Objects.equals(document.getIndexStatus(), DocumentIndexStatusEnum.BUILDING.getCode())) {
                documentMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocument>()
                    .eq(SuperAgentDocument::getId, document.getId())
                    .set(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_FAILED.getCode()));
                log.warn("文档索引状态已释放，documentId={}", document.getId());
                count++;
            }
            if (Objects.equals(document.getParseStatus(), DocumentParseStatusEnum.PARSING.getCode())) {
                documentMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocument>()
                    .eq(SuperAgentDocument::getId, document.getId())
                    .set(SuperAgentDocument::getParseStatus, DocumentParseStatusEnum.PARSE_FAILED.getCode()));
                log.warn("文档解析状态已释放，documentId={}", document.getId());
                count++;
            }
        }
        return count;
    }

    private boolean hasActiveTask(Long documentId) {
        Long active = taskMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentDocumentTask::getTaskStatus,
                DocumentTaskStatusEnum.NEW.getCode(),
                DocumentTaskStatusEnum.RUNNING.getCode()));
        return active != null && active > 0;
    }

    private void markTaskFailed(SuperAgentDocumentTask task, String errorCode, String errorMsg) {

        Date finishTime = new Date();
        Date startTime = task.getStartTime() == null ? task.getCreateTime() : task.getStartTime();
        taskMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getId, task.getId())
            .in(SuperAgentDocumentTask::getTaskStatus,
                DocumentTaskStatusEnum.NEW.getCode(),
                DocumentTaskStatusEnum.RUNNING.getCode())
            .set(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.FAILED.getCode())
            .set(SuperAgentDocumentTask::getFinishTime, finishTime)
            .set(SuperAgentDocumentTask::getCostMillis,
                startTime == null ? 0L : Math.max(0L, finishTime.getTime() - startTime.getTime()))
            .set(SuperAgentDocumentTask::getErrorCode, errorCode)
            .set(SuperAgentDocumentTask::getErrorMsg, errorMsg));
        try {
            taskLogService.saveLog(task.getId(), task.getDocumentId(),
                task.getCurrentStage() == null
                    ? DocumentTaskStageEnum.CHUNK_EXECUTE.getCode()
                    : task.getCurrentStage(),
                DocumentTaskEventTypeEnum.FAILED.getCode(),
                DocumentLogLevelEnum.ERROR.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                errorMsg,
                Map.of("errorCode", errorCode, "source", "reconciliation"));
        }
        catch (Exception exception) {
            log.error("写入对账失败日志异常，documentId={}, taskId={}", task.getDocumentId(), task.getId(), exception);
        }
    }

    private void releaseDocument(SuperAgentDocumentTask task) {

        SuperAgentDocument document = documentMapper.selectById(task.getDocumentId());
        if (document == null) {
            return;
        }
        if (Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.BUILD_INDEX.getCode())
            && Objects.equals(document.getIndexStatus(), DocumentIndexStatusEnum.BUILDING.getCode())) {
            documentMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocument>()
                .eq(SuperAgentDocument::getId, document.getId())
                .set(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_FAILED.getCode()));
        }
        if (Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())
            && Objects.equals(document.getParseStatus(), DocumentParseStatusEnum.PARSING.getCode())) {
            documentMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocument>()
                .eq(SuperAgentDocument::getId, document.getId())
                .set(SuperAgentDocument::getParseStatus, DocumentParseStatusEnum.PARSE_FAILED.getCode()));
        }
    }
}
