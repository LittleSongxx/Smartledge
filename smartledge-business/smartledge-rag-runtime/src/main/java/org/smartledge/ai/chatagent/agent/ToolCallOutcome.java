package org.smartledge.ai.chatagent.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Queryable terminal envelope for one tool call.
 *
 * <p>Timeout must be {@link AgentToolCallStatus#UNKNOWN}: the caller cannot tell whether the
 * side effect finished. Cancel is {@link AgentToolCallStatus#CANCELLED}. An unknown tool name is
 * {@link AgentToolCallStatus#REJECTED}.</p>
 */
public record ToolCallOutcome(
    String toolName,
    AgentToolCallStatus status,
    String reason,
    String message,
    long durationMs
) {

    public ToolCallOutcome {
        toolName = toolName == null ? "" : toolName;
        status = Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason;
        message = message == null ? "" : message;
    }

    public static ToolCallOutcome succeeded(String toolName, long durationMs) {
        return new ToolCallOutcome(toolName, AgentToolCallStatus.SUCCEEDED, "COMPLETED", "工具调用成功", durationMs);
    }

    public static ToolCallOutcome failed(String toolName, String reason, String message, long durationMs) {
        return new ToolCallOutcome(toolName, AgentToolCallStatus.FAILED, reason, message, durationMs);
    }

    public static ToolCallOutcome unknown(String toolName, String reason, String message, long durationMs) {
        return new ToolCallOutcome(toolName, AgentToolCallStatus.UNKNOWN, reason, message, durationMs);
    }

    public static ToolCallOutcome cancelled(String toolName, long durationMs) {
        return new ToolCallOutcome(toolName, AgentToolCallStatus.CANCELLED, "CANCELLED", "工具调用已取消", durationMs);
    }

    public static ToolCallOutcome rejected(String toolName, String reason, String message) {
        return new ToolCallOutcome(toolName, AgentToolCallStatus.REJECTED, reason, message, 0L);
    }

    public String toEnvelope() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.name());
        body.put("toolName", toolName);
        body.put("reason", reason);
        body.put("message", message);
        body.put("durationMs", durationMs);
        return toJson(body);
    }

    private static String toJson(Map<String, Object> body) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                builder.append(value);
            }
            else {
                builder.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return builder.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
