package org.smartledge.ai.chatagent.agent;

import org.smartledge.ai.rag.runtime.model.ChatToolDefinition;

/** The registered adapter owns argument validation and business effects. */
public interface AgentTool {
    ChatToolDefinition definition();
    default java.time.Duration timeout() { return java.time.Duration.ofSeconds(30); }
    String execute(String arguments, AgentToolContext context) throws Exception;
}
