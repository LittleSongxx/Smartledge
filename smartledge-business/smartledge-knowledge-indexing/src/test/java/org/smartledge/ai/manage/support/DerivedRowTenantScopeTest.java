package org.smartledge.ai.manage.support;

import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按文档派生写入的租户作用域测试（S22 批次 2）。
 *
 * <p>缺陷 S22-E：解析与索引构建在系统上下文下运行，租户拦截器跳过收窄，派生行只能取列默认值 1；
 * 文档属于租户 2 时，这些行会被错标为默认租户，检索按 {@code tenant_id} 收窄后"构建成功但读不到内容"。</p>
 *
 * <p>这里锁定新入口的四件事：①按文档作用域真的把租户交给 SQL 重写层；②退出后恢复调用前的上下文
 * （含"原来的系统上下文"这一种）；③父文档租户缺失时 fail closed，绝不用默认租户兜底；
 * ④跨租户操作即使在租户作用域内被调用，也仍然看得到全部租户。</p>
 */
class DerivedRowTenantScopeTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("按文档作用域内写入看到的租户就是父文档租户，退出后恢复")
    void perDocumentScopeExposesDocumentTenant() {
        TenantContext.setSystem();
        List<Long> seen = new ArrayList<>();
        DerivedRowTenantScope.runPerDocument(2L, () -> seen.add(TenantContext.get()));
        DerivedRowTenantScope.callPerDocument(7L, () -> {
            seen.add(TenantContext.get());
            return null;
        });

        assertThat(seen).containsExactly(2L, 7L);
        assertThat(TenantContext.get()).isEqualTo(TenantContext.SYSTEM);
    }

    @Test
    @DisplayName("按文档作用域只声明租户：不携带调用方的认证主体")
    void perDocumentScopeDropsIdentity() {
        TenantContext.setIdentity(new RequestIdentity(9L, 11L, "builder", Set.of(3L), Set.of()));

        DerivedRowTenantScope.runPerDocument(2L, () -> assertThat(TenantContext.getIdentity()).isNull());

        assertThat(TenantContext.getIdentity()).isNotNull();
    }

    @Test
    @DisplayName("父文档租户缺失时 fail closed：null 与系统标记都拒绝")
    void missingDocumentTenantFailsClosed() {
        for (Long tenantId : new Long[] {null, TenantContext.SYSTEM, 0L, -5L}) {
            assertThatThrownBy(() -> DerivedRowTenantScope.runPerDocument(tenantId, () -> {
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺少父文档租户");
        }
        assertThatThrownBy(() -> DerivedRowTenantScope.callPerDocument(null, () -> "unused"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少父文档租户");
        assertThat(DerivedRowTenantScope.requireDocumentTenant(3L)).isEqualTo(3L);
    }

    @Test
    @DisplayName("跨租户操作在租户作用域内仍然看得到全部租户，退出后回到租户作用域")
    void crossTenantScopeOverridesDocumentScope() {
        List<Object> seen = new ArrayList<>();
        DerivedRowTenantScope.runPerDocument(2L, () -> {
            seen.add(TenantContext.get());
            DerivedRowTenantScope.runCrossTenant(() -> seen.add(TenantContext.get()));
            seen.add(DerivedRowTenantScope.callCrossTenant(TenantContext::get));
            seen.add(TenantContext.get());
        });

        assertThat(seen).containsExactly(2L, TenantContext.SYSTEM, TenantContext.SYSTEM, 2L);
        assertThat(TenantContext.isPresent()).isFalse();
    }
}
