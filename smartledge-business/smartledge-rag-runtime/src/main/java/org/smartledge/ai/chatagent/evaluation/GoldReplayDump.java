package org.smartledge.ai.chatagent.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieved identities keyed by {@code rag-gold.v1} case id.
 * This is how live probe or archive identities are scored; the gold file itself stays synthetic.
 */
public record GoldReplayDump(String schemaVersion, String description, Map<String, List<String>> retrieved) {

    public static final String SCHEMA_VERSION = "rag-gold-replay.v1";

    public GoldReplayDump {
        schemaVersion = schemaVersion == null ? "" : schemaVersion;
        description = description == null ? "" : description;
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (retrieved != null) {
            retrieved.forEach((caseId, identities) -> copy.put(
                caseId == null ? "" : caseId,
                identities == null ? List.of() : List.copyOf(identities)
            ));
        }
        retrieved = Map.copyOf(copy);
    }
}
