package org.smartledge.ai.chatagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationRuntimeKeysTest {

    @Test
    @DisplayName("运行时键带租户，跨租户同 conversationId 不碰撞")
    void keysAreTenantScoped() {
        assertThat(ConversationRuntimeKeys.registryKey(1L, "same"))
            .isNotEqualTo(ConversationRuntimeKeys.registryKey(2L, "same"));
        assertThat(ConversationRuntimeKeys.leaseKey(1L, "same"))
            .isNotEqualTo(ConversationRuntimeKeys.leaseKey(2L, "same"));
        assertThat(ConversationRuntimeKeys.checkpointKey(1L, "same"))
            .isNotEqualTo(ConversationRuntimeKeys.checkpointKey(2L, "same"));
    }

    @Test
    @DisplayName("缺少租户或会话 id 时拒绝构造键")
    void rejectsBlankIdentity() {
        assertThatThrownBy(() -> ConversationRuntimeKeys.registryKey(null, "c"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ConversationRuntimeKeys.leaseKey(1L, " "))
            .isInstanceOf(IllegalStateException.class);
    }
}
