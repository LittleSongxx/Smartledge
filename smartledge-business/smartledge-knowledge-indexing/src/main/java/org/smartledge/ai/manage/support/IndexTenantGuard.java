package org.smartledge.ai.manage.support;

import org.smartledge.database.tenant.TenantContext;

/**
 * 索引检索的租户资格：没有真实租户就不得省略 filter。
 */
public final class IndexTenantGuard {

    private IndexTenantGuard() {
    }

    /**
     * @return 当前真实租户；缺失或系统标记时为空（调用方必须 fail closed，不得继续搜全集）
     */
    public static Long searchableTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0L) {
            return null;
        }
        return tenantId;
    }

    public static boolean hasSearchableTenant() {
        return searchableTenantId() != null;
    }
}
