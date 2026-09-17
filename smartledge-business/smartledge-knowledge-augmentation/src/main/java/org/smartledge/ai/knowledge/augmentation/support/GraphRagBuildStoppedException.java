package org.smartledge.ai.knowledge.augmentation.support;

import java.util.Map;

/** The caller must leave task state to its current owner after cancellation or lease loss. */
public final class GraphRagBuildStoppedException extends IllegalStateException {
    public enum Reason {
        CANCELLED, LEASE_LOST
    }

    private final Reason reason;
    private final Map<String, Object> metadata;

    public GraphRagBuildStoppedException(Reason reason) {
        this(reason, Map.of());
    }

    public GraphRagBuildStoppedException(Reason reason, Map<String, Object> metadata) {
        super("NO_COMMIT: GraphRAG execution stopped: " + reason);
        this.reason = reason;
        this.metadata = Map.copyOf(metadata);
    }

    public Reason reason() {
        return reason;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }
}
