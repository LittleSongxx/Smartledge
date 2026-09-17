package org.smartledge.ai.chatagent.model.trace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Stable schema projection shared by every producer of the ROUTE trace stage. */
public final class RouteTraceSnapshot {

    public static final String SCHEMA_VERSION = "route-trace.v1";

    private RouteTraceSnapshot() {
    }

    public static Map<String, Object> of(Substage substage) {
        return of(substage, Map.of());
    }

    public static Map<String, Object> of(Substage substage, Map<String, ?> details) {
        Objects.requireNonNull(substage, "substage");
        Map<String, ?> safeDetails = details == null ? Map.of() : details;
        if (safeDetails.containsKey("schemaVersion") || safeDetails.containsKey("routeSubstage")) {
            throw new IllegalArgumentException("ROUTE detail snapshot must not override protocol identity");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", SCHEMA_VERSION);
        snapshot.put("routeSubstage", substage.name());
        safeDetails.forEach(snapshot::put);
        return Collections.unmodifiableMap(snapshot);
    }

    public enum Substage {
        OPEN_CHAT_EXECUTION,
        AUTO_DOCUMENT_SCOPE,
        RETRIEVAL_NAVIGATION,
        CLARIFICATION_RESPONSE
    }
}
