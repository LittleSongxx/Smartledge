package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagBuildResult;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagBuildCheckpointService;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.data.SuperAgentDocumentTaskLog;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.smartledge.ai.manage.service.DocumentIndexBuildProgressCacheService;
import org.smartledge.ai.manage.service.DocumentTaskLogService;
import org.smartledge.enums.DocumentLogLevelEnum;
import org.smartledge.enums.DocumentOperatorTypeEnum;
import org.smartledge.enums.DocumentTaskEventTypeEnum;
import org.smartledge.enums.DocumentTaskStageEnum;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@AllArgsConstructor
public class DocumentGraphRagBuildCheckpointServiceImpl implements GraphRagBuildCheckpointService {

    private static final String ROOT_KEY = "graphRagBuild";
    private static final int ERROR_MESSAGE_LIMIT = 1000;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<LinkedHashMap<String, Object>> EXT_JSON_TYPE = new TypeReference<>() {
    };

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentMapper documentMapper;

    private final DocumentTaskLogService taskLogService;

    private final DocumentIndexBuildProgressCacheService progressCacheService;

    private final ObjectMapper objectMapper;

    @Override
    public void markRunning(Long documentId, Long taskId, String stage, int attempt, int maxAttempts,
            Map<String, Object> detail) {
        try {
            Map<String, Object> state = baseState("RUNNING", stage, attempt, maxAttempts);
            if (detail != null && !detail.isEmpty()) {
                state.putAll(detail);
            }
            updateTaskExtJson(taskId, state);
            saveLog(documentId, taskId, DocumentTaskEventTypeEnum.START, DocumentLogLevelEnum.INFO,
                    "GraphRAG 构建 checkpoint 更新: " + stage, state);
        }
        catch (Exception exception) {
            log.warn("GraphRAG checkpoint RUNNING 更新失败: documentId={}, taskId={}, stage={}", documentId, taskId, stage,
                    exception);
        }
    }

    @Override
    public void markOutcome(Long documentId, Long taskId, GraphRagBuildResult result, int attempt, int maxAttempts) {
        try {
            String disposition = result == null || result.getOuterTaskDisposition() == null ? null
                    : result.getOuterTaskDisposition().name();
            String status = disposition == null ? "KG_COMMITTED" : disposition;
            Map<String, Object> state = baseState(status, "OUTCOME", attempt, maxAttempts);
            if (result != null) {
                putIfNotNull(state, "entityCount", result.getEntityCount());
                putIfNotNull(state, "relationCount", result.getRelationCount());
                putIfNotNull(state, "evidenceCount", result.getEvidenceCount());
                putIfNotNull(state, "communityCount", result.getCommunityCount());
                putIfNotNull(state, "graphPersistenceOutcome", result.getGraphPersistenceOutcome());
                putIfNotNull(state, "graphPersistenceReason", result.getGraphPersistenceReason());
                putIfNotNull(state, "kgCommitted", result.getKgCommitted());
                putIfNotNull(state, "typedIndexOutcome", result.getTypedIndexOutcome());
                putIfNotNull(state, "crossDocumentIndexOutcome", result.getCrossDocumentIndexOutcome());
                putIfNotNull(state, "derivedIndexOutcome", result.getDerivedIndexOutcome());
                putIfNotNull(state, "observationProjectionOutcome", result.getObservationProjectionOutcome());
                putIfNotNull(state, "outerTaskDisposition", result.getOuterTaskDisposition());
                putIfNotNull(state, "pythonInvocationOutcome", result.getPythonInvocationOutcome());
                putIfNotNull(state, "advisorInvocationOutcome", result.getAdvisorInvocationOutcome());
                putIfNotNull(state, "pythonExtractionStatus", result.getPythonExtractionStatus());
                putIfNotNull(state, "advisorReason", result.getAdvisorReason());
                if (result.getDegradationReasons() != null && !result.getDegradationReasons().isEmpty()) {
                    state.put("degradationReasons", result.getDegradationReasons());
                }
                if (result.getExtractionMetadata() != null && !result.getExtractionMetadata().isEmpty()) {
                    state.put("extractorMetadata", result.getExtractionMetadata());
                }
            }
            updateTaskExtJson(taskId, state, true);
            saveLog(documentId, taskId, DocumentTaskEventTypeEnum.COMPLETE, DocumentLogLevelEnum.INFO,
                    "GraphRAG outcome 已投影。", state);
        }
        catch (Exception exception) {
            throw new IllegalStateException("GraphRAG outcome observation projection failed", exception);
        }
    }

    private void putIfNotNull(Map<String, Object> state, String key, Object value) {
        if (value != null) {
            state.put(key, value);
        }
    }

    @Override
    public void markRetry(Long documentId, Long taskId, String stage, int attempt, int maxAttempts, long backoffMillis,
            Throwable exception) {
        try {
            Map<String, Object> state = baseState("RUNNING", "RETRY_WAIT", attempt, maxAttempts);
            state.put("failedStage", stage);
            state.put("nextAttempt", attempt + 1);
            state.put("backoffMillis", backoffMillis);
            state.put("errorType", exception == null ? null : exception.getClass().getName());
            state.put("errorMessage", limit(exception == null ? null : exception.getMessage(), ERROR_MESSAGE_LIMIT));
            updateTaskExtJson(taskId, state, true);
            saveLog(documentId, taskId, DocumentTaskEventTypeEnum.FAILED, DocumentLogLevelEnum.WARN,
                    "GraphRAG 构建尝试失败，准备重试: " + stage, state);
        }
        catch (Exception checkpointException) {
            log.warn("GraphRAG checkpoint RETRY 更新失败: documentId={}, taskId={}, stage={}", documentId, taskId, stage,
                    checkpointException);
        }
    }

    @Override
    public void markRejected(Long documentId, Long taskId, String stage, int maxAttempts, String message,
            Map<String, Object> detail) {
        try {
            Map<String, Object> state = baseState("REJECTED", stage, 0, maxAttempts);
            state.put("message", message);
            if (detail != null && !detail.isEmpty()) {
                state.putAll(detail);
            }
            saveLog(documentId, taskId, DocumentTaskEventTypeEnum.FAILED, DocumentLogLevelEnum.WARN, message, state);
        }
        catch (Exception exception) {
            log.warn("GraphRAG checkpoint REJECTED 记录失败: documentId={}, taskId={}, stage={}", documentId, taskId, stage,
                    exception);
        }
    }

    @Override
    public void markFailure(Long documentId, Long taskId, String stage, int attempt, int maxAttempts,
            Throwable exception) {
        try {
            Map<String, Object> state = baseState("FAILED", stage, attempt, maxAttempts);
            state.put("errorType", exception == null ? null : exception.getClass().getName());
            state.put("errorMessage", limit(exception == null ? null : exception.getMessage(), ERROR_MESSAGE_LIMIT));
            updateTaskExtJson(taskId, state, true);
            saveLog(documentId, taskId, DocumentTaskEventTypeEnum.FAILED, DocumentLogLevelEnum.ERROR,
                    "GraphRAG 构建失败: " + stage, state);
        }
        catch (Exception checkpointException) {
            log.warn("GraphRAG checkpoint FAILED 更新失败: documentId={}, taskId={}, stage={}", documentId, taskId, stage,
                    checkpointException);
        }
    }

    private Map<String, Object> baseState(String status, String stage, int attempt, int maxAttempts) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", status);
        state.put("stage", stage);
        state.put("attempt", attempt);
        state.put("maxAttempts", maxAttempts);
        state.put("lastCheckpointTime", LocalDateTime.now().format(TIME_FORMATTER));
        return state;
    }

    private void updateTaskExtJson(Long taskId, Map<String, Object> graphRagState) throws JsonProcessingException {
        updateTaskExtJson(taskId, graphRagState, false);
    }

    private void updateTaskExtJson(Long taskId, Map<String, Object> graphRagState, boolean preservePreviousDetail)
            throws JsonProcessingException {
        if (taskId == null) {
            return;
        }
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        Map<String, Object> extJson = readExtJson(task.getExtJson());
        extJson.put(ROOT_KEY, mergePreviousGraphRagState(extJson, graphRagState, preservePreviousDetail));
        SuperAgentDocumentTask update = new SuperAgentDocumentTask();
        update.setExtJson(objectMapper.writeValueAsString(extJson));
        taskMapper.update(update,
                new LambdaUpdateWrapper<SuperAgentDocumentTask>().eq(SuperAgentDocumentTask::getId, taskId));
    }

    private Map<String, Object> mergePreviousGraphRagState(Map<String, Object> extJson,
            Map<String, Object> graphRagState, boolean preservePreviousDetail) {
        if (!preservePreviousDetail) {
            return graphRagState;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        Object previous = extJson.get(ROOT_KEY);
        if (previous instanceof Map<?, ?> previousState) {
            previousState.forEach((key, value) -> {
                if (key instanceof String keyText) {
                    merged.put(keyText, value);
                }
            });
        }
        merged.putAll(graphRagState);
        return merged;
    }

    private Map<String, Object> readExtJson(String extJson) {
        if (StrUtil.isBlank(extJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(extJson, EXT_JSON_TYPE);
        }
        catch (Exception exception) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("legacyExtJson", extJson);
            return result;
        }
    }

    private void saveLog(Long documentId, Long taskId, DocumentTaskEventTypeEnum eventType,
            DocumentLogLevelEnum logLevel, String content, Map<String, Object> detail) {
        SuperAgentDocumentTaskLog taskLog = taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.GRAPH_RAG.getCode(), eventType.getCode(), logLevel.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(), null, content, detail);
        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        progressCacheService.update(document, task, taskLog);
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
