package org.smartledge.ai.chatagent.agent;

/** Terminal status of one Agent tool invocation. Timeout is UNKNOWN, not a known failure. */
public enum AgentToolCallStatus {
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    CANCELLED,
    REJECTED
}
