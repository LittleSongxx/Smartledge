package org.smartledge.ai.manage.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过期派生行判定口径测试（S22 批次 3）。
 *
 * <p>删除是不可逆动作，因此判定必须先有测试：尤其是"拿不到权威就什么都不删"与"当前任务永不过期"
 * 这两条——它们决定了清理脚本会不会把唯一一套有效数据删掉。</p>
 */
class StaleDerivedIndexRowPolicyTest {

    @Test
    @DisplayName("除当前有效任务外都是过期行")
    void everythingButCurrentTaskIsStale() {
        Set<Long> stale = StaleDerivedIndexRowPolicy.staleTaskIds(3L, List.of(1L, 3L, 5L));

        assertThat(stale).containsExactly(1L, 5L);
    }

    @Test
    @DisplayName("拿不到当前有效任务时什么都不判过期（不猜、不删）")
    void missingAuthorityStalesNothing() {
        for (Long lastIndexTaskId : new Long[] {null, 0L, -1L}) {
            assertThat(StaleDerivedIndexRowPolicy.staleTaskIds(lastIndexTaskId, List.of(1L, 2L))).isEmpty();
            assertThat(StaleDerivedIndexRowPolicy.isStale(lastIndexTaskId, 1L)).isFalse();
        }
        assertThat(StaleDerivedIndexRowPolicy.staleTaskIds(3L, null)).isEmpty();
    }

    @Test
    @DisplayName("只有当前任务、以及重复/空值入参时的行为")
    void currentTaskAndNoisyInputs() {
        assertThat(StaleDerivedIndexRowPolicy.staleTaskIds(3L, List.of(3L))).isEmpty();
        assertThat(StaleDerivedIndexRowPolicy.staleTaskIds(3L, Arrays.asList(1L, 1L, null, 0L, -2L)))
                .containsExactly(1L);
        assertThat(StaleDerivedIndexRowPolicy.isStale(3L, 3L)).isFalse();
        assertThat(StaleDerivedIndexRowPolicy.isStale(3L, 4L)).isTrue();
        assertThat(StaleDerivedIndexRowPolicy.isStale(3L, null)).isFalse();
    }

    @Test
    @DisplayName("真实形状：5 套派生行（1 套有效 + 4 套过期）")
    void realShapeFiveTaskSets() {
        Set<Long> stale = StaleDerivedIndexRowPolicy.staleTaskIds(2523494840625586176L,
                List.of(2523494840625586176L, 2522855268455573562L, 2522855199736100853L,
                        2522855165376358890L, 2522855131016618326L));

        assertThat(stale).containsExactly(2522855268455573562L, 2522855199736100853L, 2522855165376358890L,
                2522855131016618326L);
    }
}
