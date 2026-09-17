package org.smartledge.ai.manage.config;

import org.smartledge.ai.knowledge.augmentation.port.GraphRagToolException;
import org.smartledge.ai.manage.vo.ExecutionFailureDiagnosticVo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ExecutionFailureDiagnosticProjector {

    public static final String SCHEMA_VERSION = "execution-failure-diagnostic.v1";

    private final SystemConfigRegistry registry;

    public ExecutionFailureDiagnosticProjector(SystemConfigRegistry registry) {
        this.registry = registry;
    }

    public ExecutionFailureDiagnosticVo configurationFailure(SystemConfigValidationException failure) {
        List<ExecutionFailureDiagnosticVo.Parameter> parameters = failure.configKeys().stream()
                .map(key -> parameter(key, failure.values().get(key), failure.values().get(key), null, "UNKNOWN"))
                .filter(Objects::nonNull)
                .toList();
        ExecutionFailureDiagnosticVo.Issue issue = new ExecutionFailureDiagnosticVo.Issue(
                bounded(failure.ruleId(), 100), bounded(failure.getMessage(), 500), parameters,
                bounded(failure.constraint(), 500), boundedList(failure.suggestions(), 5, 300));
        return new ExecutionFailureDiagnosticVo(SCHEMA_VERSION, bounded(failure.ruleId(), 100), "CONFIGURATION",
                "CONFIGURATION", "SAVE_SYSTEM_CONFIG", "ERROR", "CONFIRMED", List.of(issue), false, null,
                false, new ExecutionFailureDiagnosticVo.Provenance(null, null, null, "JAVA_MANAGEMENT"));
    }

    public ExecutionFailureDiagnosticVo graphFailure(Throwable failure, Long documentId, Long taskId,
            String stage, String operation) {
        GraphRagToolException tool = findToolFailure(failure);
        if (tool == null) {
            ExecutionFailureDiagnosticVo.Issue issue = new ExecutionFailureDiagnosticVo.Issue(
                    "UNLOCATED_EXECUTION_FAILURE", "当前错误无法可靠定位到具体参数。", List.of(), null,
                    List.of("检查任务日志、Python rag-tools 服务状态和上游模型状态后再重试。"));
            return new ExecutionFailureDiagnosticVo(SCHEMA_VERSION, "UNLOCATED_EXECUTION_FAILURE", "UNKNOWN",
                    bounded(stage, 80), bounded(operation, 80), "ERROR", "UNLOCATED", List.of(issue), null, null,
                    false, new ExecutionFailureDiagnosticVo.Provenance(documentId, taskId, null, "JAVA_MANAGEMENT"));
        }

        Map<String, Object> diagnostics = tool.diagnostics();
        String category = tool.category().name();
        String code = stringValue(diagnostics.get("errorCode"), category);
        List<String> keys = parameterKeys(diagnostics, tool.category());
        String primaryKey = primaryKey(diagnostics, keys);
        Object configured = diagnostics.get("configuredLimit");
        Object actual = diagnostics.get("actualLength");
        String measurementKind = stringValue(diagnostics.get("measurementKind"), "UNKNOWN");
        List<ExecutionFailureDiagnosticVo.Parameter> parameters = keys.stream()
                .map(key -> parameter(key, key.equals(primaryKey) ? configured : null,
                        key.equals(primaryKey) ? configured : null, key.equals(primaryKey) ? actual : null,
                        key.equals(primaryKey) ? measurementKind : "UNKNOWN"))
                .filter(Objects::nonNull)
                .toList();
        String message = message(tool.category(), configured, actual, measurementKind);
        ExecutionFailureDiagnosticVo.Issue issue = new ExecutionFailureDiagnosticVo.Issue(code, message, parameters,
                constraint(tool.category(), configured, actual), suggestions(tool.category()));
        Integer upstreamStatus = diagnostics.get("upstreamStatus") instanceof Number number
                ? number.intValue() : null;
        boolean unlocated = tool.category() == GraphRagToolException.Category.UNKNOWN
                || tool.category() == GraphRagToolException.Category.PROTOCOL;
        return new ExecutionFailureDiagnosticVo(SCHEMA_VERSION, code, category, bounded(stage, 80),
                bounded(operation, 80), "ERROR", unlocated ? "UNLOCATED"
                        : keys.isEmpty() ? "RELATED_FOR_INVESTIGATION" : "CONFIRMED",
                List.of(issue), null, upstreamStatus, false,
                new ExecutionFailureDiagnosticVo.Provenance(documentId, taskId,
                        stringValue(diagnostics.get("batchId"), null), stringValue(diagnostics.get("layer"), null)));
    }

    private ExecutionFailureDiagnosticVo.Parameter parameter(String key, Object configuredValue,
            Object effectiveValue, Object actualValue, String measurementKind) {
        try {
            SystemConfigRegistry.ConfigDefinition definition = registry.require(key);
            Object configured = safeValue(definition, configuredValue);
            Object effective = safeValue(definition, effectiveValue);
            return new ExecutionFailureDiagnosticVo.Parameter(key, definition.label(), "SYSTEM", definition.unit(),
                    configured, effective, definition.sensitive() ? null : actualValue,
                    definition.sensitive() ? "UNKNOWN" : bounded(measurementKind, 40), definition.effectiveMode(),
                    effectiveModeLabel(definition.effectiveMode()),
                    new ExecutionFailureDiagnosticVo.Target("SYSTEM_CONFIG", definition.categoryKey(), key, null, null));
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private Object safeValue(SystemConfigRegistry.ConfigDefinition definition, Object value) {
        if (!definition.sensitive()) {
            return value;
        }
        return value == null ? null : String.valueOf(value).isBlank() ? "未配置" : "已配置";
    }

    private List<String> parameterKeys(Map<String, Object> diagnostics, GraphRagToolException.Category category) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (diagnostics.get("parameterKeys") instanceof List<?> values) {
            values.stream().filter(String.class::isInstance).map(String.class::cast).limit(12).forEach(keys::add);
        }
        if (keys.isEmpty()) {
            switch (category) {
            case CONTEXT_LIMIT -> keys.addAll(List.of("graphRag.extraction.inputTokenBudget",
                    "graphRag.model.modelContextTokens", "graphRag.model.outputReserve",
                    "graphRag.model.promptReserve"));
            case OUTPUT_TRUNCATED -> keys.addAll(List.of("graphRag.model.outputReserve",
                    "graphRag.extraction.inputTokenBudget", "graphRag.model.modelContextTokens",
                    "graphRag.model.promptReserve"));
            case CONNECTION, CONNECT_TIMEOUT -> keys.addAll(List.of("ragTools.baseUrl", "ragTools.connectTimeoutMs"));
            case READ_TIMEOUT -> keys.addAll(List.of("graphRag.execution.toolBudgetMillis",
                    "ragTools.graphExtractReadTimeoutMs", "graphRag.execution.documentBudgetMillis"));
            default -> {
            }
            }
        }
        return keys.stream().filter(this::registered).limit(12).toList();
    }

    private boolean registered(String key) {
        try {
            registry.require(key);
            return true;
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    private String primaryKey(Map<String, Object> diagnostics, List<String> keys) {
        String resource = stringValue(diagnostics.get("resource"), "");
        String layer = stringValue(diagnostics.get("layer"), "");
        if ("inputTokens".equals(resource)) return "graphRag.extraction.inputTokenBudget";
        if ("outputTokens".equals(resource)) return "graphRag.model.outputReserve";
        if ("responseBytes".equals(resource) && "PYTHON_MODEL".equals(layer)) {
            return "graphRag.model.modelResponseMaxBytes";
        }
        return keys.isEmpty() ? null : keys.get(0);
    }

    private String message(GraphRagToolException.Category category, Object configured, Object actual,
            String measurementKind) {
        if (category == GraphRagToolException.Category.CONTEXT_LIMIT && configured != null && actual != null) {
            return ("ESTIMATE".equals(measurementKind) ? "估算输入 " : "输入 ") + actual
                    + " token，超过当轮有效预算 " + configured + " token。";
        }
        return switch (category) {
        case CONTEXT_LIMIT -> "上游报告上下文容量不足，但没有提供可核验的实际 Token 数。";
        case OUTPUT_TRUNCATED -> "模型输出达到当轮输出上限，结果被截断。";
        case CONNECTION, CONNECT_TIMEOUT -> "Java 无法连接 Python rag-tools 服务。";
        case READ_TIMEOUT -> "GraphRAG 调用超过当轮读取或执行预算。";
        case AUTHENTICATION -> "上游认证失败；诊断不会显示密钥或令牌内容。";
        case RATE_LIMIT -> "上游限流，当前调用未完成。";
        case UPSTREAM_SERVER -> "上游服务返回故障，当前调用未完成。";
        case RESOURCE_LIMIT -> "GraphRAG 执行超过已配置资源边界。";
        default -> "GraphRAG 执行失败，当前只能提供相关排查参数。";
        };
    }

    private String constraint(GraphRagToolException.Category category, Object configured, Object actual) {
        if (category == GraphRagToolException.Category.CONTEXT_LIMIT && configured != null) {
            return actual == null ? "实际输入 Token 未报告；当轮有效预算为 " + configured + " token。"
                    : "输入 Token 必须不大于当轮有效预算 " + configured + " token。";
        }
        return null;
    }

    private List<String> suggestions(GraphRagToolException.Category category) {
        return switch (category) {
        case CONTEXT_LIMIT -> List.of("缩小来源单元或批次。", "核对供应商模型容量后再调整输入、输出和安全余量。");
        case OUTPUT_TRUNCATED -> List.of("减少单批来源，或在模型容量允许范围内调整输出预留。",
                "注意：增加输出预留会减少可用输入空间。");
        case CONNECTION, CONNECT_TIMEOUT -> List.of("先检查 Python rag-tools 服务可达性和服务地址。",
                "仅在确认连接建立过慢时再调整连接超时。");
        case READ_TIMEOUT -> List.of("核对工具预算、HTTP 读取超时和文档总预算分别发生在哪一层。",
                "确认服务健康后再重试；不要用单一超时覆盖所有层级。");
        case AUTHENTICATION -> List.of("检查上游凭据是否已配置且仍有效；不要在日志或工单中粘贴密钥。");
        case RATE_LIMIT -> List.of("按上游 Retry-After 等待后重试，或降低并发并检查配额。");
        case UPSTREAM_SERVER -> List.of("检查上游服务状态；恢复后按原任务策略重试。");
        case RESOURCE_LIMIT -> List.of("先确认具体资源和发生层，再评估缩小输入或提高对应上限的资源代价。");
        default -> List.of("检查任务日志、Python rag-tools 服务状态和上游响应后再决定是否调整参数。");
        };
    }

    private GraphRagToolException findToolFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof GraphRagToolException tool) return tool;
        }
        return null;
    }

    private String effectiveModeLabel(String mode) {
        return switch (mode) {
        case SystemConfigRegistry.RESTART_REQUIRED -> "重启应用后生效";
        case SystemConfigRegistry.NEW_BUILD_TASK -> "新构建任务生效";
        default -> "新会话生效";
        };
    }

    private List<String> boundedList(List<String> values, int limit, int maxLength) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        values.stream().filter(Objects::nonNull).limit(limit).forEach(value -> result.add(bounded(value, maxLength)));
        return List.copyOf(result);
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : bounded(String.valueOf(value), 128);
    }

    private String bounded(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "…";
    }
}
