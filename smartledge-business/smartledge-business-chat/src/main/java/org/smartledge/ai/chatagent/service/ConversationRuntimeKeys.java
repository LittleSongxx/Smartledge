package org.smartledge.ai.chatagent.service;

/**
 * 会话运行时键：进程内任务表、Redis 租约、checkpoint 都必须带租户，禁止裸 conversationId 寻址。
 */
public final class ConversationRuntimeKeys {

    public static final String CHAT_RUNNING_LEASE_PREFIX = "chat:running:";

    private ConversationRuntimeKeys() {
    }

    public static String registryKey(Long tenantId, String conversationId) {
        return requireTenant(tenantId) + ":" + requireConversation(conversationId);
    }

    public static String leaseKey(Long tenantId, String conversationId) {
        return CHAT_RUNNING_LEASE_PREFIX + registryKey(tenantId, conversationId);
    }

    public static String checkpointKey(Long tenantId, String conversationId) {
        return registryKey(tenantId, conversationId);
    }

    private static Long requireTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalStateException("会话运行时键缺少租户");
        }
        return tenantId;
    }

    private static String requireConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalStateException("会话运行时键缺少 conversationId");
        }
        return conversationId;
    }
}
