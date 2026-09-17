package org.smartledge.ai.chatagent.rag.executor;

import org.smartledge.ai.chatagent.rag.model.ExecutionMode;
import org.smartledge.ai.chatagent.service.TaskInfo;
import reactor.core.publisher.Flux;

/**
 * @description: 统一对话执行器抽象
 * @author: Song
 **/

public interface ConversationExecutor {

    ExecutionMode mode();

    Flux<String> execute(TaskInfo taskInfo);
}
