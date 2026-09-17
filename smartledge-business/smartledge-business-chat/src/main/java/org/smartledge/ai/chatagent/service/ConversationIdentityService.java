package org.smartledge.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.auth.support.AuthFailureException;
import org.smartledge.database.tenant.RequestIdentity;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * 会话 id 的签发与认领。
 *
 * <p>客户端自选裸 id 不能直接落库：新会话一律由服务端签发；已存在且属于当前用户的 id 可以续用；
 * 他租户已占用同一 {@code dialogue_code} 时拒绝，避免跨租户碰撞。</p>
 */
@Service
public class ConversationIdentityService {

    private final ConversationArchiveStore conversationArchiveStore;

    public ConversationIdentityService(ConversationArchiveStore conversationArchiveStore) {
        this.conversationArchiveStore = conversationArchiveStore;
    }

    public String resolveForLaunch(String requestedConversationId, RequestIdentity identity) {
        if (identity == null) {
            throw new AuthFailureException(401, "请先登录");
        }
        String requested = StrUtil.trim(requestedConversationId);
        if (StrUtil.isBlank(requested)) {
            return issue();
        }
        Optional<Long> owner = conversationArchiveStore.findOwnerUserId(requested);
        if (owner.isPresent()) {
            Long ownerUserId = owner.get();
            if (ownerUserId != 0L && ownerUserId.equals(identity.userId())) {
                return requested;
            }
            throw new AuthFailureException(403, "会话不存在或不属于当前账号");
        }
        if (conversationArchiveStore.existsInOtherTenant(requested, identity.tenantId())) {
            throw new AuthFailureException(403, "会话不存在或不属于当前账号");
        }
        return issue();
    }

    public static String issue() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
