package org.smartledge.ai.chatagent.evaluation;

import java.util.List;

public record ReplayableGoldSet(String schemaVersion, String description, List<GoldCase> cases) {

    public ReplayableGoldSet {
        schemaVersion = schemaVersion == null ? "" : schemaVersion;
        description = description == null ? "" : description;
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record GoldCase(String id, String query, List<String> relevantIdentities, int k) {
        public GoldCase {
            id = id == null ? "" : id;
            query = query == null ? "" : query;
            relevantIdentities = relevantIdentities == null ? List.of() : List.copyOf(relevantIdentities);
            k = Math.max(1, k);
        }
    }
}
