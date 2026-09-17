package org.smartledge.ai.auth.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.exception.SuperAgentFrameException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoChatQuotaTest {

    @Test
    @DisplayName("同一用户在小时窗口内超过上限后拒绝")
    void hourlyLimitRejectsFurtherQuestions() {
        DemoChatQuota quota = new DemoChatQuota();
        for (int i = 0; i < DemoChatQuota.HOURLY_LIMIT; i++) {
            quota.consumeOrThrow(201L);
        }

        assertThatThrownBy(() -> quota.consumeOrThrow(201L))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining(PortfolioPermissions.QUOTA_MESSAGE);
        assertThatCode(() -> quota.consumeOrThrow(202L)).doesNotThrowAnyException();
    }
}
