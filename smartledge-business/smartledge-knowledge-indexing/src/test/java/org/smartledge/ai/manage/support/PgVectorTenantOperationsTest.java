package org.smartledge.ai.manage.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量库租户作用域的契约测试。
 *
 * <p>对应 B3 的 RLS 兜底要求：每条向量语句都必须落在带 {@code app.tenant_id} 的事务里。
 * 这里锁定最容易出错的一环 —— 租户的取值：缺失、系统标记或非法值都必须直接拒绝，
 * 绝不允许静默改用默认租户（那会让 RLS 与断言都变成"看似生效"）。</p>
 */
class PgVectorTenantOperationsTest {

    @Test
    @DisplayName("真实租户 id 原样返回")
    void realTenantIsAccepted() {
        assertThat(PgVectorTenantOperations.requireTenant(1L)).isEqualTo(1L);
        assertThat(PgVectorTenantOperations.requireTenant(2L)).isEqualTo(2L);
    }

    @Test
    @DisplayName("缺少租户时拒绝，不提供默认租户兜底")
    void missingTenantIsRejected() {
        assertThatThrownBy(() -> PgVectorTenantOperations.requireTenant(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("缺少租户");
    }

    @Test
    @DisplayName("系统标记与非法租户值被拒绝")
    void systemOrInvalidTenantIsRejected() {
        assertThatThrownBy(() -> PgVectorTenantOperations.requireTenant(-1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("需要真实租户");
        assertThatThrownBy(() -> PgVectorTenantOperations.requireTenant(0L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("需要真实租户");
    }
}
