package org.smartledge.ai.chatagent.rag.executor;

import org.smartledge.ai.chatagent.rag.model.ExecutionMode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * @description: 对话执行器注册表
 * @author: Song
 **/

@Component
public class ConversationExecutorRegistry {

    private final Map<ExecutionMode, ConversationExecutor> executorMap = new EnumMap<>(ExecutionMode.class);

    public ConversationExecutorRegistry(List<ConversationExecutor> executors) {
        for (ConversationExecutor executor : executors) {
            if (executorMap.putIfAbsent(executor.mode(), executor) != null) {
                throw new IllegalStateException("执行模式存在重复执行器: " + executor.mode());
            }
        }
    }

    public ConversationExecutor get(ExecutionMode mode) {
        ConversationExecutor executor = executorMap.get(mode);
        if (executor == null) {
            throw new IllegalStateException("未找到执行模式对应的执行器: " + mode);
        }
        return executor;
    }
}
