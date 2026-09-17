package org.smartledge.ai.manage.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.database.tenant.TenantContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentWithdrawalPolicyTest {

    @Test
    @DisplayName("墓碑行即使对象还在也召不回")
    void tombstoneIsNotRetrievableWhileObjectStillExists() {
        Instant now = Instant.parse("2026-09-17T15:00:00Z");
        assertThat(DocumentWithdrawalPolicy.stillRetrievable(0, null, now, true)).isFalse();
        assertThat(DocumentWithdrawalPolicy.stillRetrievable(1, null, now, true)).isTrue();
    }

    @Test
    @DisplayName("删除中途对象已删、行还在时只要已墓碑检索仍空")
    void crashWindowAfterObjectDeleteStaysUnsearchable() {
        Instant now = Instant.parse("2026-09-17T15:00:00Z");
        assertThat(DocumentWithdrawalPolicy.stillRetrievable(0, null, now, false)).isFalse();
        assertThat(DocumentWithdrawalPolicy.requiredOrder()).containsExactly(
            DocumentWithdrawalPolicy.Phase.TOMBSTONE_SEARCHABLE_STORE,
            DocumentWithdrawalPolicy.Phase.DELETE_OBJECT_BYTES,
            DocumentWithdrawalPolicy.Phase.PURGE_ROWS
        );
        assertThat(DocumentWithdrawalPolicy.requiredOrder().indexOf(
            DocumentWithdrawalPolicy.Phase.TOMBSTONE_SEARCHABLE_STORE))
            .isLessThan(DocumentWithdrawalPolicy.requiredOrder().indexOf(
                DocumentWithdrawalPolicy.Phase.DELETE_OBJECT_BYTES));
    }

    @Test
    @DisplayName("没有租户上下文不得省略索引租户过滤")
    void missingTenantFailsClosed() {
        TenantContext.clear();
        try {
            assertThat(IndexTenantGuard.hasSearchableTenant()).isFalse();
            assertThat(IndexTenantGuard.searchableTenantId()).isNull();
            TenantContext.set(1L);
            assertThat(IndexTenantGuard.searchableTenantId()).isEqualTo(1L);
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("权威任务提交后旧世代应被标为待墓碑")
    void staleGenerationSelectedAfterCommit() {
        assertThat(StaleDerivedIndexRowPolicy.staleTaskIds(100L, List.of(90L, 100L, 80L)))
            .containsExactly(90L, 80L);
        assertThat(StaleDerivedIndexRowPolicy.staleTaskIds(null, List.of(90L))).isEmpty();
    }
}
