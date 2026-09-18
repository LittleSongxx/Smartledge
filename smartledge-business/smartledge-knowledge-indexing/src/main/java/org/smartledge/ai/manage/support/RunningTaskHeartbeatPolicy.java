package org.smartledge.ai.manage.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 执行中任务的心跳判定。
 *
 * <p>对账失联窗口原先只看 {@code startTime}。GraphRAG 抽取会连续写入
 * {@code graphRagBuild.lastCheckpointTime}，长文档在预算内仍会被 2 小时看门狗误杀。
 * 有可解析的 checkpoint 时，失联窗口从最近一次心跳起算；解析不了则没有心跳，
 * 仍按 startTime 窗口处理。</p>
 */
public final class RunningTaskHeartbeatPolicy {

    static final String GRAPH_RAG_BUILD = "graphRagBuild";

    static final String LAST_CHECKPOINT_TIME = "lastCheckpointTime";

    static final DateTimeFormatter CHECKPOINT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RunningTaskHeartbeatPolicy() {
    }

    public static boolean hasRecentHeartbeat(String extJson, long nowMillis, long staleTimeoutMillis) {
        Long heartbeatMillis = lastHeartbeatMillis(extJson);
        if (heartbeatMillis == null || staleTimeoutMillis <= 0) {
            return false;
        }
        return nowMillis - heartbeatMillis < staleTimeoutMillis;
    }

    public static Long lastHeartbeatMillis(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(extJson);
            JsonNode graphRagState = root == null ? null : root.get(GRAPH_RAG_BUILD);
            if (graphRagState == null || !graphRagState.isObject()) {
                return null;
            }
            String raw = text(graphRagState.get(LAST_CHECKPOINT_TIME));
            if (raw.isEmpty()) {
                return null;
            }
            LocalDateTime checkpoint = LocalDateTime.parse(raw, CHECKPOINT_TIME);
            return checkpoint.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        catch (Exception exception) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }
}
