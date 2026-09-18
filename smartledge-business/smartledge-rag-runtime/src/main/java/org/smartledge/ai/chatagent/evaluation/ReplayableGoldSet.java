package org.smartledge.ai.chatagent.evaluation;

import java.util.List;
import java.util.Locale;

public record ReplayableGoldSet(String schemaVersion, String description, List<GoldCase> cases) {

    public ReplayableGoldSet {
        schemaVersion = schemaVersion == null ? "" : schemaVersion;
        description = description == null ? "" : description;
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record GoldCase(String id, String query, List<String> relevantIdentities, String caseType, int k) {

        public static final String CASE_TYPE_RANKING = "RANKING";
        public static final String CASE_TYPE_EMPTY_SCOPE = "EMPTY_SCOPE";

        public GoldCase {
            id = id == null ? "" : id;
            query = query == null ? "" : query;
            relevantIdentities = relevantIdentities == null ? List.of() : List.copyOf(relevantIdentities);
            // 兼容旧文件：缺省按 RANKING 解释。"空 relevantIdentities 是否合法"由
            // ReplayableGoldSetScorer 的校验裁决，不再有"留空=空范围"的隐式语义。
            caseType = caseType == null || caseType.isBlank()
                ? CASE_TYPE_RANKING
                : caseType.trim().toUpperCase(Locale.ROOT);
            k = Math.max(1, k);
        }

        public boolean isEmptyScope() {
            return CASE_TYPE_EMPTY_SCOPE.equals(caseType);
        }
    }
}
