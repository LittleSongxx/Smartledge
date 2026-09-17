package org.smartledge.ai.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.data.SuperAgentDocumentTaskLog;
import org.smartledge.ai.manage.service.DocumentParseRouteProgressCacheService;
import org.smartledge.ai.manage.vo.DocumentParseRouteProgressVo;
import org.smartledge.ai.manage.vo.DocumentTaskLogVo;
import org.smartledge.core.RedisKeyManage;
import org.smartledge.enums.DocumentLogLevelEnum;
import org.smartledge.enums.DocumentTaskEventTypeEnum;
import org.smartledge.enums.DocumentTaskStageEnum;
import org.smartledge.redis.RedisCache;
import org.smartledge.redis.RedisKeyBuild;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@AllArgsConstructor
@Service
public class RedisDocumentParseRouteProgressCacheService implements DocumentParseRouteProgressCacheService {

    private static final long TTL_HOURS = 5L;
    private static final int MAX_LOG_SIZE = 80;

    private final RedisCache redisCache;

    private final ObjectMapper objectMapper;

    @Override
    public DocumentParseRouteProgressVo get(Long taskId) {
        if (taskId == null) {
            return null;
        }
        try {
            return redisCache.get(key(taskId), DocumentParseRouteProgressVo.class);
        }
        catch (Exception exception) {
            log.warn("读取解析路由 Redis 进度快照失败，taskId={}, message={}", taskId, exception.getMessage());
            return null;
        }
    }

    @Override
    public void init(SuperAgentDocument document, SuperAgentDocumentTask task) {
        update(document, task, null, List.of(), null);
    }

    @Override
    public void update(SuperAgentDocument document, SuperAgentDocumentTask task) {
        update(document, task, null, List.of(), null);
    }

    @Override
    public void update(SuperAgentDocument document, SuperAgentDocumentTask task, SuperAgentDocumentTaskLog latestLog) {
        update(document, task, null, List.of(), latestLog);
    }

    @Override
    public void update(SuperAgentDocument document,
                       SuperAgentDocumentTask task,
                       SuperAgentDocumentStrategyPlan plan,
                       List<SuperAgentDocumentStrategyStep> steps,
                       SuperAgentDocumentTaskLog latestLog) {
        if (document == null || task == null || task.getId() == null) {
            return;
        }
        try {
            DocumentParseRouteProgressVo previous = get(task.getId());
            List<DocumentTaskLogVo> logs = previous == null || previous.getLogs() == null
                ? new ArrayList<>() : new ArrayList<>(previous.getLogs());
            if (latestLog != null) {
                logs.add(toLogVo(latestLog));
            }
            logs = DocumentParseRouteProgressAssembler.deduplicateAndTrimLogs(logs, MAX_LOG_SIZE);
            Long latestLogId = DocumentParseRouteProgressAssembler.latestLogId(logs);
            long totalLogCount = previous == null || previous.getTotalLogCount() == null ? 0L : previous.getTotalLogCount();
            if (latestLog != null && (previous == null || previous.getLogs() == null
                || previous.getLogs().stream().noneMatch(item -> Objects.equals(item.getId(), latestLog.getId())))) {
                totalLogCount++;
            }

            DocumentParseRouteProgressVo snapshot = DocumentParseRouteProgressAssembler.build(
                document,
                task,
                plan,
                steps == null ? List.of() : steps,
                logs,
                totalLogCount,
                objectMapper
            );
            snapshot.setLatestLogId(latestLogId);
            redisCache.set(key(task.getId()), snapshot, TTL_HOURS, TimeUnit.HOURS);
        }
        catch (Exception exception) {
            log.warn("写入解析路由 Redis 进度快照失败，documentId={}, taskId={}, message={}",
                document.getId(), task.getId(), exception.getMessage());
        }
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

    private RedisKeyBuild key(Long taskId) {
        return RedisKeyBuild.createRedisKey(RedisKeyManage.DOCUMENT_PARSE_ROUTE_PROGRESS, taskId);
    }

    private String stageName(Integer stage) {
        DocumentTaskStageEnum item = stage == null ? null : DocumentTaskStageEnum.getRc(stage);
        return item == null ? "" : item.getMsg();
    }

    private String eventTypeName(Integer eventType) {
        DocumentTaskEventTypeEnum item = eventType == null ? null : DocumentTaskEventTypeEnum.getRc(eventType);
        return item == null ? "" : item.getMsg();
    }

    private String logLevelName(Integer logLevel) {
        DocumentLogLevelEnum item = logLevel == null ? null : DocumentLogLevelEnum.getRc(logLevel);
        return item == null ? "" : item.getMsg();
    }
}
