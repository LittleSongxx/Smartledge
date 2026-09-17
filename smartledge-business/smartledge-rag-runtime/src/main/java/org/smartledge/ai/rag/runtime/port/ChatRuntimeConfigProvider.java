package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.config.ChatAgentProperties;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;

/** Supplies the current chat and RAG configuration to runtime consumers. */
public interface ChatRuntimeConfigProvider {

    ChatAgentProperties currentChat();

    ChatRagProperties currentRag();
}
