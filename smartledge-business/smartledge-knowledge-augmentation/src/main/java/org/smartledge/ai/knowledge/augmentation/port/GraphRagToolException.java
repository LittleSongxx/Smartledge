package org.smartledge.ai.knowledge.augmentation.port;

import java.util.LinkedHashMap;
import java.util.Map;

/** Consumer-owned failure vocabulary; transport adapters never decide retries. */
public final class GraphRagToolException extends RuntimeException {
    public enum Category {
        CONNECTION, CONNECT_TIMEOUT, READ_TIMEOUT, RATE_LIMIT, UPSTREAM_SERVER, AUTHENTICATION, CONFIGURATION, PROTOCOL,
        RESOURCE_LIMIT, OUTPUT_TRUNCATED, CONTEXT_LIMIT, CANCELLED, DOCUMENT_TIMEOUT, SATURATED, UNKNOWN
    }

    private final Category category;
    private final Map<String, Object> diagnostics;

    public GraphRagToolException(Category category, Map<String, Object> diagnostics, Throwable cause) {
        super("GraphRAG tool failed: category=" + category + ", " + diagnostics, cause);
        this.category = category;
        LinkedHashMap<String, Object> values = new LinkedHashMap<>(diagnostics);
        values.put("category", category.name());
        this.diagnostics = Map.copyOf(values);
    }

    public Category category() {
        return category;
    }

    public Map<String, Object> diagnostics() {
        return diagnostics;
    }

    public long retryAfterMillis() {
        Object value = diagnostics.get("retryAfterMillis");
        return value instanceof Number n ? Math.max(0, n.longValue()) : 0;
    }
}
