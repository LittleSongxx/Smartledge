package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.RagRuntimeOptions;

/**
 * RAG 策略拥有的全局运行时配置端口。实现细节可以来自 YAML 或数据库。
 */
@FunctionalInterface
public interface GlobalRagRuntimeConfigProvider {

    RagRuntimeOptions currentOptions();
}
