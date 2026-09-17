package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.data.SuperAgentDocumentTaskLog;
import org.smartledge.ai.manage.service.DocumentIndexBuildProgressCacheService;
import org.smartledge.ai.manage.vo.DocumentIndexBuildProgressVo;
import org.smartledge.ai.manage.vo.DocumentTaskLogVo;
import org.smartledge.core.RedisKeyManage;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentIndexStatusEnum;
import org.smartledge.enums.DocumentLogLevelEnum;
import org.smartledge.enums.DocumentTaskEventTypeEnum;
import org.smartledge.enums.DocumentTaskStageEnum;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;
import org.smartledge.redis.RedisCache;
import org.smartledge.redis.RedisKeyBuild;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@AllArgsConstructor
@Service
public class RedisDocumentIndexBuildProgressCacheService implements DocumentIndexBuildProgressCacheService {

    private static final long TTL_HOURS = 5L;
    private static final int MAX_LOG_SIZE = 80;

    private final RedisCache redisCache;

    @Override
    public DocumentIndexBuildProgressVo get(Long taskId) {
        if (taskId == null) {
            return null;
        }
        try {
            return redisCache.get(key(taskId), DocumentIndexBuildProgressVo.class);
        }
        catch (Exception exception) {
            log.warn("读取索引构建 Redis 进度快照失败，taskId={}, message={}", taskId, exception.getMessage());
            return null;
        }
    }

    @Override
    public void init(SuperAgentDocument document, SuperAgentDocumentTask task) {
        update(document, task, null);
    }

    @Override
    public void update(SuperAgentDocument document, SuperAgentDocumentTask task) {
        update(document, task, null);
    }

    @Override
    public void update(SuperAgentDocument document, SuperAgentDocumentTask task, SuperAgentDocumentTaskLog latestLog) {
        if (document == null || task == null || task.getId() == null) {
            return;
        }
        try {
            DocumentIndexBuildProgressVo previous = get(task.getId());
            List<DocumentTaskLogVo> logs = previous == null || previous.getLogs() == null
                ? new ArrayList<>() : new ArrayList<>(previous.getLogs());
            if (latestLog != null) {
                logs.add(toLogVo(latestLog));
            }
            logs = trimLogs(deduplicateLogs(logs));
            Long latestLogId = logs.stream()
                .map(DocumentTaskLogVo::getId)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(previous == null ? null : previous.getLatestLogId());
            long totalLogCount = previous == null || previous.getTotalLogCount() == null ? 0L : previous.getTotalLogCount();
            if (latestLog != null) {
                totalLogCount++;
            }

            DocumentIndexBuildProgressVo snapshot = new DocumentIndexBuildProgressVo(
                document.getId(),
                document.getIndexStatus(),
                indexStatusName(document.getIndexStatus()),
                task.getId(),
                task.getTaskType(),
                taskTypeName(task.getTaskType()),
                task.getTaskStatus(),
                taskStatusName(task.getTaskStatus()),
                task.getCurrentStage(),
                stageName(task.getCurrentStage()),
                task.getStartTime(),
                task.getFinishTime(),
                task.getCostMillis(),
                elapsedMillis(task),
                task.getErrorCode(),
                task.getErrorMsg(),
                task.getExtJson(),
                building(document, task),
                latestLogId,
                totalLogCount,
                logs
            );
            redisCache.set(key(task.getId()), snapshot, TTL_HOURS, TimeUnit.HOURS);
        }
        catch (Exception exception) {
            log.warn("写入索引构建 Redis 进度快照失败，documentId={}, taskId={}, message={}",
                document.getId(), task.getId(), exception.getMessage());
        }
    }

    private List<DocumentTaskLogVo> deduplicateLogs(List<DocumentTaskLogVo> logs) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }
        List<DocumentTaskLogVo> result = new ArrayList<>();
        for (DocumentTaskLogVo logItem : logs.stream()
            .filter(item -> item != null && item.getId() != null)
            .sorted(Comparator
                .comparing(DocumentTaskLogVo::getCreateTime, Comparator.nullsLast(Date::compareTo))
                .thenComparing(DocumentTaskLogVo::getId, Comparator.nullsLast(Long::compareTo)))
            .toList()) {
            boolean exists = result.stream().anyMatch(existing -> Objects.equals(existing.getId(), logItem.getId()));
            if (!exists) {
                result.add(logItem);
            }
        }
        return result;
    }

    private List<DocumentTaskLogVo> trimLogs(List<DocumentTaskLogVo> logs) {
        if (logs == null || logs.size() <= MAX_LOG_SIZE) {
            return logs == null ? List.of() : logs;
        }
        return new ArrayList<>(logs.subList(logs.size() - MAX_LOG_SIZE, logs.size()));
    }

    private DocumentTaskLogVo toLogVo(SuperAgentDocumentTaskLog logRecord) {
        return new DocumentTaskLogVo(
            logRecord.getId(),
            logRecord.getStageType(),
            stageName(logRecord.getStageType()),
            logRecord.getEventType(),
            eventTypeName(logRecord.getEventType()),
            logRecord.getLogLevel(),
            logLevelName(logRecord.getLogLevel()),
            logRecord.getContent(),
            logRecord.getDetailJson(),
            logRecord.getCreateTime()
        );
    }

    private boolean building(SuperAgentDocument document, SuperAgentDocumentTask task) {
        return task != null && (
            Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.NEW.getCode())
                || Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.RUNNING.getCode())
                || Objects.equals(document.getIndexStatus(), DocumentIndexStatusEnum.BUILDING.getCode())
        );
    }

    private Long elapsedMillis(SuperAgentDocumentTask task) {
        if (task == null) {
            return null;
        }
        if (task.getCostMillis() != null && task.getCostMillis() > 0) {
            return task.getCostMillis();
        }
        if (task.getStartTime() == null) {
            return 0L;
        }
        Date endTime = task.getFinishTime() == null ? new Date() : task.getFinishTime();
        return Math.max(0L, endTime.getTime() - task.getStartTime().getTime());
    }

    private RedisKeyBuild key(Long taskId) {
        return RedisKeyBuild.createRedisKey(RedisKeyManage.DOCUMENT_INDEX_BUILD_PROGRESS, taskId);
    }

    private String indexStatusName(Integer status) {
        DocumentIndexStatusEnum item = DocumentIndexStatusEnum.getRc(status);
        return item == null ? "" : item.getMsg();
    }

    private String taskTypeName(Integer type) {
        DocumentTaskTypeEnum item = DocumentTaskTypeEnum.getRc(type);
        return item == null ? "" : item.getMsg();
    }

    private String taskStatusName(Integer status) {
        DocumentTaskStatusEnum item = DocumentTaskStatusEnum.getRc(status);
        return item == null ? "" : item.getMsg();
    }

    private String stageName(Integer stage) {
        DocumentTaskStageEnum item = DocumentTaskStageEnum.getRc(stage);
        return item == null ? "" : item.getMsg();
    }

    private String eventTypeName(Integer eventType) {
        DocumentTaskEventTypeEnum item = DocumentTaskEventTypeEnum.getRc(eventType);
        return item == null ? "" : item.getMsg();
    }

    private String logLevelName(Integer logLevel) {
        DocumentLogLevelEnum item = DocumentLogLevelEnum.getRc(logLevel);
        return item == null ? "" : item.getMsg();
    }
}
