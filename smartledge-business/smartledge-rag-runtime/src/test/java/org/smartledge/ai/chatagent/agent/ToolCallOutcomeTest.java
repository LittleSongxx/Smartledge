package org.smartledge.ai.chatagent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallOutcomeTest {

    @Test
    @DisplayName("超时是 UNKNOWN，取消是 CANCELLED，未知工具是 REJECTED")
    void terminalStatusesStayDistinct() {
        assertThat(ToolCallOutcome.unknown("knowledge_search", "TIMEOUT", "工具超时，结果未知", 10L).status())
            .isEqualTo(AgentToolCallStatus.UNKNOWN);
        assertThat(ToolCallOutcome.cancelled("knowledge_search", 1L).status())
            .isEqualTo(AgentToolCallStatus.CANCELLED);
        assertThat(ToolCallOutcome.rejected("missing", "UNKNOWN_TOOL", "未知工具").status())
            .isEqualTo(AgentToolCallStatus.REJECTED);
        assertThat(ToolCallOutcome.unknown("tavily_search", "TIMEOUT", "工具超时，结果未知", 0L).toEnvelope())
            .contains("\"status\":\"UNKNOWN\"")
            .contains("TIMEOUT")
            .doesNotContain("Tool failed");
    }
}
