package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.vo.DocumentParseRouteProgressVo;
import org.smartledge.ai.manage.vo.DocumentTaskLogVo;
import org.smartledge.enums.DocumentFileTypeEnum;
import org.smartledge.enums.DocumentParseStatusEnum;
import org.smartledge.enums.DocumentStrategyPipelineTypeEnum;
import org.smartledge.enums.DocumentStrategyStatusEnum;
import org.smartledge.enums.DocumentTaskStageEnum;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DocumentParseRouteProgressAssembler {

    static final int DEFAULT_LOG_LIMIT = 60;
    static final int MAX_LOG_LIMIT = 200;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private DocumentParseRouteProgressAssembler() {
    }

    static DocumentParseRouteProgressVo build(SuperAgentDocument document,
                                              SuperAgentDocumentTask task,
                                              SuperAgentDocumentStrategyPlan plan,
                                              List<SuperAgentDocumentStrategyStep> steps,
                                              List<DocumentTaskLogVo> logs,
                                              Long totalLogCount,
                                              ObjectMapper objectMapper) {
        Map<String, Object> taskExtJson = readMap(task == null ? null : task.getExtJson(), objectMapper);
        Map<String, Object> trace = objectMap(taskExtJson.get("parserTraceMetadata"));
        Map<String, Object> parserLogDetail = parserLogDetail(logs, objectMapper);
        if (trace.isEmpty()) {
            trace = objectMap(parserLogDetail.get("parserTraceMetadata"));
        }
        Map<String, Object> parseLogDetail = latestContentParseLogDetail(logs, objectMapper);

        Long latestLogId = latestLogId(logs);
        Integer currentStage = task == null ? null : task.getCurrentStage();
        DocumentTaskStageEnum stage = stageEnum(currentStage);
        Long planId = plan == null ? (document == null ? null : document.getCurrentPlanId()) : plan.getId();
        boolean planReady = planId != null;

        return new DocumentParseRouteProgressVo(
            document == null ? null : document.getId(),
            task == null ? null : task.getId(),
            task == null ? null : task.getTaskType(),
            enumMsg(taskTypeEnum(task == null ? null : task.getTaskType())),
            task == null ? null : task.getTaskStatus(),
            enumMsg(taskStatusEnum(task == null ? null : task.getTaskStatus())),
            currentStage,
            enumMsg(stage),
            stage == null ? "" : stage.name(),
            stageLabel(stage),
            document == null ? null : document.getParseStatus(),
            enumMsg(parseStatusEnum(document == null ? null : document.getParseStatus())),
            document == null ? null : document.getStrategyStatus(),
            enumMsg(strategyStatusEnum(document == null ? null : document.getStrategyStatus())),
            parseMode(document, trace),
            firstText(trace.get("providerName"), parserLogDetail.get("parserProviderName"), parseLogDetail.get("parserProviderName"), taskExtJson.get("parserProviderName")),
            firstText(trace.get("providerVersion"), parserLogDetail.get("parserProviderVersion"), parseLogDetail.get("parserProviderVersion"), taskExtJson.get("parserProviderVersion")),
            stringValue(trace.get("jobId")),
            integerValue(trace.get("pageCount")),
            integerValue(firstNonNull(trace.get("blockCount"), parserLogDetail.get("blockCount"), parseLogDetail.get("blockCount"))),
            integerValue(trace.get("tableCount")),
            integerValue(trace.get("figureCount")),
            integerValue(trace.get("captionCount")),
            planReady,
            planId,
            plan == null ? null : plan.getRecommendReason(),
            countSteps(steps, DocumentStrategyPipelineTypeEnum.PARENT),
            countSteps(steps, DocumentStrategyPipelineTypeEnum.CHILD),
            task == null ? null : task.getStartTime(),
            task == null ? null : task.getFinishTime(),
            task == null ? null : task.getCostMillis(),
            elapsedMillis(task),
            latestLogId,
            totalLogCount == null ? 0L : totalLogCount,
            logs == null ? List.of() : logs,
            task == null ? null : task.getErrorCode(),
            task == null ? null : task.getErrorMsg(),
            running(task)
        );
    }

    static List<DocumentTaskLogVo> filterLogs(List<DocumentTaskLogVo> logs, Long sinceLogId, int logLimit) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }
        int limit = resolveLogLimit(logLimit);
        List<DocumentTaskLogVo> filtered = logs.stream()
            .filter(item -> item != null && item.getId() != null)
            .filter(item -> sinceLogId == null || sinceLogId <= 0 || item.getId() > sinceLogId)
            .sorted(logComparator())
            .toList();
        if (filtered.size() <= limit) {
            return filtered;
        }
        return new ArrayList<>(filtered.subList(filtered.size() - limit, filtered.size()));
    }

    static List<DocumentTaskLogVo> deduplicateAndTrimLogs(List<DocumentTaskLogVo> logs, int maxLogSize) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }
        List<DocumentTaskLogVo> result = new ArrayList<>();
        logs.stream()
            .filter(item -> item != null && item.getId() != null)
            .sorted(logComparator())
            .forEach(item -> {
                boolean exists = result.stream().anyMatch(existing -> Objects.equals(existing.getId(), item.getId()));
                if (!exists) {
                    result.add(item);
                }
            });
        if (result.size() <= maxLogSize) {
            return result;
        }
        return new ArrayList<>(result.subList(result.size() - maxLogSize, result.size()));
    }

    static Long latestLogId(List<DocumentTaskLogVo> logs) {
        if (logs == null || logs.isEmpty()) {
            return null;
        }
        return logs.stream()
            .map(DocumentTaskLogVo::getId)
            .filter(Objects::nonNull)
            .max(Long::compareTo)
            .orElse(null);
    }

    static Long cachedElapsedMillis(DocumentParseRouteProgressVo cached) {
        if (cached == null) {
            return null;
        }
        if (cached.getCostMillis() != null && cached.getCostMillis() > 0) {
            return cached.getCostMillis();
        }
        if (cached.getStartTime() == null) {
            return cached.getElapsedMillis();
        }
        Date endTime = cached.getFinishTime() == null ? new Date() : cached.getFinishTime();
        return Math.max(0L, endTime.getTime() - cached.getStartTime().getTime());
    }

    static int resolveLogLimit(Integer requestedLimit, Integer configuredLimit) {
        int configured = configuredLimit == null || configuredLimit <= 0 ? DEFAULT_LOG_LIMIT : configuredLimit;
        int limit = requestedLimit == null || requestedLimit <= 0 ? configured : requestedLimit;
        return resolveLogLimit(limit);
    }

    private static int resolveLogLimit(int requestedLimit) {
        int limit = requestedLimit <= 0 ? DEFAULT_LOG_LIMIT : requestedLimit;
        return Math.max(1, Math.min(limit, MAX_LOG_LIMIT));
    }

    private static Comparator<DocumentTaskLogVo> logComparator() {
        return Comparator
            .comparing(DocumentTaskLogVo::getCreateTime, Comparator.nullsLast(Date::compareTo))
            .thenComparing(DocumentTaskLogVo::getId, Comparator.nullsLast(Long::compareTo));
    }

    private static Map<String, Object> parserLogDetail(List<DocumentTaskLogVo> logs, ObjectMapper objectMapper) {
        if (logs == null || logs.isEmpty()) {
            return Map.of();
        }
        return logs.stream()
            .filter(item -> item != null && Objects.equals(item.getStageType(), DocumentTaskStageEnum.CONTENT_PARSE.getCode()))
            .sorted(logComparator().reversed())
            .map(item -> readMap(item.getDetailJson(), objectMapper))
            .filter(item -> !objectMap(item.get("parserTraceMetadata")).isEmpty()
                || StrUtil.isNotBlank(stringValue(item.get("parserProviderName")))
                || StrUtil.isNotBlank(stringValue(item.get("parserProviderVersion"))))
            .findFirst()
            .orElse(Map.of());
    }

    private static Map<String, Object> latestContentParseLogDetail(List<DocumentTaskLogVo> logs, ObjectMapper objectMapper) {
        if (logs == null || logs.isEmpty()) {
            return Map.of();
        }
        return logs.stream()
            .filter(item -> item != null && Objects.equals(item.getStageType(), DocumentTaskStageEnum.CONTENT_PARSE.getCode()))
            .sorted(logComparator().reversed())
            .map(item -> readMap(item.getDetailJson(), objectMapper))
            .filter(item -> !item.isEmpty())
            .findFirst()
            .orElse(Map.of());
    }

    private static Map<String, Object> readMap(String json, ObjectMapper objectMapper) {
        if (StrUtil.isBlank(json) || objectMapper == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        }
        catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (StrUtil.isNotBlank(text)) {
                return text;
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String parseMode(SuperAgentDocument document, Map<String, Object> trace) {
        String provider = stringValue(trace.get("providerName")).toLowerCase();
        if (provider.contains("aliyun") || provider.contains("docmind") || StrUtil.isNotBlank(stringValue(trace.get("jobId")))) {
            return "ALIYUN_DOCMIND";
        }
        if (provider.contains("text") || provider.contains("light") || provider.contains("native")) {
            return "LIGHT_TEXT";
        }
        DocumentFileTypeEnum fileType = document == null || document.getFileType() == null
            ? null : DocumentFileTypeEnum.getRc(document.getFileType());
        if (fileType == DocumentFileTypeEnum.TXT || fileType == DocumentFileTypeEnum.MD || fileType == DocumentFileTypeEnum.HTML) {
            return "LIGHT_TEXT";
        }
        if (fileType != null) {
            return "ALIYUN_DOCMIND";
        }
        return "UNKNOWN";
    }

    private static String stageLabel(DocumentTaskStageEnum stage) {
        if (stage == null) {
            return "";
        }
        return switch (stage) {
            case FILE_UPLOAD -> "文件上传";
            case CONTENT_PARSE -> "内容解析/OCR";
            case STRATEGY_ROUTE -> "策略推荐";
            case STRATEGY_CONFIRM -> "等待人工确认";
            default -> stage.getMsg();
        };
    }

    private static int countSteps(List<SuperAgentDocumentStrategyStep> steps,
                                  DocumentStrategyPipelineTypeEnum pipelineType) {
        if (steps == null || pipelineType == null) {
            return 0;
        }
        return (int) steps.stream()
            .filter(item -> item != null && pipelineType.getCode().equalsIgnoreCase(StrUtil.blankToDefault(item.getPipelineType(), "")))
            .count();
    }

    private static Long elapsedMillis(SuperAgentDocumentTask task) {
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

    private static Boolean running(SuperAgentDocumentTask task) {
        if (task == null) {
            return false;
        }
        return Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.NEW.getCode())
            || Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.RUNNING.getCode());
    }

    private static String enumMsg(Object enumObject) {
        if (enumObject == null) {
            return "";
        }
        if (enumObject instanceof DocumentTaskTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskStageEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentParseStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategyStatusEnum value) {
            return value.getMsg();
        }
        return "";
    }

    private static DocumentTaskTypeEnum taskTypeEnum(Integer code) {
        if (code == null) {
            return null;
        }
        return DocumentTaskTypeEnum.getRc(code);
    }

    private static DocumentTaskStatusEnum taskStatusEnum(Integer code) {
        if (code == null) {
            return null;
        }
        return DocumentTaskStatusEnum.getRc(code);
    }

    private static DocumentTaskStageEnum stageEnum(Integer code) {
        if (code == null) {
            return null;
        }
        return DocumentTaskStageEnum.getRc(code);
    }

    private static DocumentParseStatusEnum parseStatusEnum(Integer code) {
        if (code == null) {
            return null;
        }
        return DocumentParseStatusEnum.getRc(code);
    }

    private static DocumentStrategyStatusEnum strategyStatusEnum(Integer code) {
        if (code == null) {
            return null;
        }
        return DocumentStrategyStatusEnum.getRc(code);
    }
}
