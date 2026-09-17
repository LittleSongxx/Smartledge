package org.smartledge.ai.rag.runtime.model;

import java.util.List;

/** Complete conversation message; partial stream fragments never enter history. */
public record ChatMessage(String role, String content, List<ChatResult.ToolCall> toolCalls, String toolCallId) {
    public ChatMessage {
        if (!List.of("system", "user", "assistant", "tool").contains(role)) throw new IllegalArgumentException("message role");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (!role.equals("assistant") && !toolCalls.isEmpty()) throw new IllegalArgumentException("tool calls require assistant");
        if (role.equals("tool") && (toolCallId == null || toolCallId.isBlank())) throw new IllegalArgumentException("tool result identity");
    }
    public static ChatMessage text(String role, String content) { return new ChatMessage(role, content, List.of(), null); }
}
