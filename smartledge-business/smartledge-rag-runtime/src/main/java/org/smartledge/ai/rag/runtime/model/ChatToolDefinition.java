package org.smartledge.ai.rag.runtime.model;

import java.util.Map;

/** Registered tool schema, interpreted by the tool adapter and serialized by transport. */
public record ChatToolDefinition(String name, String description, Map<String, Object> parameters) {
    public ChatToolDefinition { parameters = Map.copyOf(parameters); }
}
