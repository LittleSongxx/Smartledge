package org.smartledge.ai.rag.runtime.model;

/** Sanitized transport failure; never includes credentials, prompts, or provider response bodies. */
public final class ModelCallException extends RuntimeException {
    public enum Kind { AUTHENTICATION, INVALID_REQUEST, RATE_LIMIT, SERVER, TRANSPORT, TIMEOUT, CANCELLED, INVALID_RESPONSE, LIMIT, BACKPRESSURE, INCOMPLETE }
    private final Kind kind;
    private final int status;
    private final String phase;
    public ModelCallException(Kind kind, int status, String phase) {
        super("Model request failed: phase=" + phase + ", kind=" + kind + ", status=" + status);
        this.kind = kind;
        this.status = status;
        this.phase = phase;
    }
    public Kind kind() { return kind; }
    public int status() { return status; }
    public String phase() { return phase; }
    public boolean retryable() {
        return kind == Kind.RATE_LIMIT || kind == Kind.SERVER || kind == Kind.TRANSPORT || kind == Kind.TIMEOUT;
    }
}
