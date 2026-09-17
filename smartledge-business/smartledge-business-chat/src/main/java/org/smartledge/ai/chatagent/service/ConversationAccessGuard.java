package org.smartledge.ai.chatagent.service;

import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.ai.auth.support.AuthFailureException;
import org.springframework.stereotype.Component;

/**
 * 会话归属守卫（会话与记忆摘要的访问边界）。
 *
 * <p>租户边界不足以保护会话：同一租户内任何用户拿到 conversationId 就能读别人的会话与记忆摘要。
 * 归属由**会话表自己**承载（{@code smartledge_chat_dialogue.user_id}），在请求入口校验一次，
 * 下游只消费结果。</p>
 *
 * <h2>fail closed</h2>
 *
 * <ul>
 *   <li>没有认证主体 → 401；</li>
 *   <li>会话不存在、无归属（0，B3 之前的历史会话）、归属他人 → 统一 403，
 *       文案不区分这几种情况，避免通过错误信息探测"某个 conversationId 是否存在"；</li>
 *   <li>无归属会话**不会被任何请求认领**：认领会把"任何人可读"变成"先到先得"，是更隐蔽的越权。</li>
 * </ul>
 */
@Component
public class ConversationAccessGuard {

    private final ConversationArchiveStore conversationArchiveStore;

    public ConversationAccessGuard(ConversationArchiveStore conversationArchiveStore) {
        this.conversationArchiveStore = conversationArchiveStore;
    }

    /** 当前登录用户 id；未认证即拒绝。 */
    public Long requireCurrentUserId() {
        return requireIdentity().userId();
    }

    /**
     * 会话必须存在且属于当前身份（读、写、停止、重置、重建摘要都走这里）。
     */
    public void requireOwned(String conversationId) {
        RequestIdentity identity = requireIdentity();
        Long ownerUserId = conversationArchiveStore.findOwnerUserId(conversationId).orElse(null);
        if (ownerUserId == null || ownerUserId == 0L || !ownerUserId.equals(identity.userId())) {
            throw new AuthFailureException(403, "会话不存在或不属于当前账号");
        }
    }

    /**
     * 会话要么属于当前身份，要么尚不存在（本轮将创建）。
     *
     * <p>用于对话入口：新会话允许创建，已存在的会话必须是自己的。</p>
     */
    public void requireOwnedOrNew(String conversationId) {
        RequestIdentity identity = requireIdentity();
        Long ownerUserId = conversationArchiveStore.findOwnerUserId(conversationId).orElse(null);
        if (ownerUserId == null) {
            return;
        }
        if (ownerUserId == 0L || !ownerUserId.equals(identity.userId())) {
            throw new AuthFailureException(403, "会话不存在或不属于当前账号");
        }
    }

    /** 当前身份；未认证抛 401。 */
    public RequestIdentity requireIdentity() {
        RequestIdentity identity = TenantContext.getIdentity();
        if (identity == null) {
            throw new AuthFailureException(401, "请先登录");
        }
        return identity;
    }
}
