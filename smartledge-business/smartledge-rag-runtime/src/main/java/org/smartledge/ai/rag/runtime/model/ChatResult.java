package org.smartledge.ai.rag.runtime.model;

import java.util.List;

/** Provider facts, without estimated usage or framework types. */
public record ChatResult(String text, String responseId, String model, String finishReason,
                         Usage usage, List<ToolCall> toolCalls) {
    public ChatResult { toolCalls = List.copyOf(toolCalls); }
    public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) { }
    public record ToolCall(String id, String name, String arguments) { }
}
