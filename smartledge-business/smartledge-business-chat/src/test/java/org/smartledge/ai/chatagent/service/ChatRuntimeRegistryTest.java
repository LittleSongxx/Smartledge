package org.smartledge.ai.chatagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.support.StreamEventMetadata;
import org.smartledge.enums.ChatQueryMode;
import reactor.core.publisher.Sinks;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRuntimeRegistryTest {

    @Test
    @DisplayName("跨租户相同 conversationId 互不影响")
    void tenantIsolatesRuntimeKeys() {
        ChatRuntimeRegistry registry = new ChatRuntimeRegistry();
        TaskInfo tenantOne = task(1L, "shared-id");
        TaskInfo tenantTwo = task(2L, "shared-id");

        assertThat(registry.register(tenantOne)).isTrue();
        assertThat(registry.register(tenantTwo)).isTrue();
        assertThat(registry.get(1L, "shared-id")).containsSame(tenantOne);
        assertThat(registry.get(2L, "shared-id")).containsSame(tenantTwo);

        registry.remove(1L, "shared-id", tenantOne);
        assertThat(registry.get(1L, "shared-id")).isEmpty();
        assertThat(registry.get(2L, "shared-id")).containsSame(tenantTwo);
    }

    private TaskInfo task(Long tenantId, String conversationId) {
        return new TaskInfo(
            conversationId,
            1L,
            "q",
            ChatQueryMode.OPEN_CHAT,
            "trace",
            tenantId,
            null,
            "",
            null,
            null,
            LocalDate.now(),
            "",
            null,
            null,
            null,
            Sinks.many().unicast().onBackpressureBuffer(),
            new StreamEventMetadata(conversationId, 1L),
            ConversationRuntimeKeys.leaseKey(tenantId, conversationId),
            "owner",
            List.of(),
            List.of(),
            Set.of(),
            System.currentTimeMillis()
        );
    }
}
