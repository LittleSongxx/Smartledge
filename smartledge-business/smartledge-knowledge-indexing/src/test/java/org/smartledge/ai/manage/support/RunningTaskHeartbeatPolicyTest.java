package org.smartledge.ai.manage.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class RunningTaskHeartbeatPolicyTest {

    private static final long TWO_HOURS = 7_200_000L;

    @Test
    @DisplayName("无 checkpoint 或解析失败时没有心跳，对账仍按 startTime 窗口处理")
    void missingOrInvalidCheckpointIsNotAHeartbeat() {
        long now = System.currentTimeMillis();
        assertThat(RunningTaskHeartbeatPolicy.hasRecentHeartbeat(null, now, TWO_HOURS)).isFalse();
        assertThat(RunningTaskHeartbeatPolicy.hasRecentHeartbeat("{}", now, TWO_HOURS)).isFalse();
        assertThat(RunningTaskHeartbeatPolicy.hasRecentHeartbeat(
            "{\"graphRagBuild\":{\"status\":\"RUNNING\"}}", now, TWO_HOURS)).isFalse();
        assertThat(RunningTaskHeartbeatPolicy.hasRecentHeartbeat(
            "{\"graphRagBuild\":{\"lastCheckpointTime\":\"not-a-time\"}}", now, TWO_HOURS)).isFalse();
        assertThat(RunningTaskHeartbeatPolicy.lastHeartbeatMillis("{")).isNull();
    }

    @Test
    @DisplayName("两小时内的 GraphRAG checkpoint 算作仍存活，即使任务早已开工")
    void freshGraphRagCheckpointKeepsTaskAlive() {
        long now = System.currentTimeMillis();
        String checkpoint = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(now - 30 * 60_000L), ZoneId.systemDefault())
            .format(RunningTaskHeartbeatPolicy.CHECKPOINT_TIME);
        String extJson = "{\"graphRagBuild\":{\"status\":\"RUNNING\",\"stage\":\"EXTRACTING\","
            + "\"lastCheckpointTime\":\"" + checkpoint + "\"}}";

        assertThat(RunningTaskHeartbeatPolicy.hasRecentHeartbeat(extJson, now, TWO_HOURS)).isTrue();
        assertThat(RunningTaskHeartbeatPolicy.hasRecentHeartbeat(extJson, now + TWO_HOURS, TWO_HOURS)).isFalse();
    }
}
