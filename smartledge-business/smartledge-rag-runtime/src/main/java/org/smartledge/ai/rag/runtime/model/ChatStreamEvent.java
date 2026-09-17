package org.smartledge.ai.rag.runtime.model;

/** One protocol event. Text and reasoning are deltas; facts are the latest cumulative metadata.
 * Tool arguments are untrusted fragments until completed supplies identity-validated calls; adapters validate complete raw arguments.
 * completed means a validated finish reason followed by the transport [DONE] marker.
 */
public record ChatStreamEvent(String textDelta, String reasoningDelta, ToolDelta toolDelta,
                              ChatResult facts, boolean completed) {
    public record ToolDelta(int index, String id, String name, String arguments) { }
}
