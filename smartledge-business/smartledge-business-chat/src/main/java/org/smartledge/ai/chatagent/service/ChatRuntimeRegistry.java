package org.smartledge.ai.chatagent.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进行中对话任务表。键必须带租户，禁止裸 conversationId 跨租户互踩。
 */
@Component
public class ChatRuntimeRegistry {

    private final ConcurrentMap<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    public boolean register(TaskInfo taskInfo) {
        return taskMap.putIfAbsent(ConversationRuntimeKeys.registryKey(taskInfo.tenantId(), taskInfo.conversationId()),
            taskInfo) == null;
    }

    public Optional<TaskInfo> get(Long tenantId, String conversationId) {
        return Optional.ofNullable(taskMap.get(ConversationRuntimeKeys.registryKey(tenantId, conversationId)));
    }

    public void remove(Long tenantId, String conversationId) {
        taskMap.remove(ConversationRuntimeKeys.registryKey(tenantId, conversationId));
    }

    public void remove(Long tenantId, String conversationId, TaskInfo expectedTaskInfo) {
        if (tenantId == null || conversationId == null || expectedTaskInfo == null) {
            return;
        }
        taskMap.remove(ConversationRuntimeKeys.registryKey(tenantId, conversationId), expectedTaskInfo);
    }
}
