package org.smartledge.ai.auth.support;

import org.smartledge.database.tenant.RequestIdentity;

/**
 * 作品集对外试用账号的权限标记。
 *
 * <p>持有 {@link #DEMO} 的主体仍然走普通 RBAC 与文档 ACL，但额外受
 * {@link PortfolioDemoInterceptor}、观测归属收窄和提问配额约束。
 * 不得把该标记授给 ADMIN / CURATOR。</p>
 */
public final class PortfolioPermissions {

    public static final String DEMO = "portfolio:demo";

    public static final int MAX_QUESTION_CHARS = 800;

    public static final String WRITE_BLOCKED_MESSAGE = "试用账号为只读展示，不能执行该操作。";

    public static final String OPEN_CHAT_BLOCKED_MESSAGE = "试用账号仅允许基于已授权文档提问。";

    public static final String QUOTA_MESSAGE = "试用账号提问次数已达上限，请稍后再试。";

    public static final String QUESTION_TOO_LONG_MESSAGE = "试用账号单次提问不能超过 800 字。";

    private PortfolioPermissions() {
    }

    public static boolean isDemo(RequestIdentity identity) {
        return identity != null && identity.hasPermission(DEMO);
    }

    public static boolean isDemo(AuthenticatedPrincipal principal) {
        return principal != null && principal.hasPermission(DEMO);
    }
}
