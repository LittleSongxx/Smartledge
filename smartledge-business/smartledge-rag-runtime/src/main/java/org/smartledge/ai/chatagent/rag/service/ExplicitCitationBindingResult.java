package org.smartledge.ai.chatagent.rag.service;

import org.smartledge.ai.chatagent.model.SearchReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S25 分权结果：检索来源按 Prompt 渲染序，显式引用按合法 {@code [n]} 首次出现序。
 * 过渡期 {@code sourceSnapshotIdentities} 只等于 explicit。
 */
public record ExplicitCitationBindingResult(
    List<SearchReference> retrievedSources,
    List<SearchReference> explicitCitations,
    List<String> retrievedSourceIdentities,
    List<String> explicitCitationIdentities
) {

    public ExplicitCitationBindingResult {
        retrievedSources = List.copyOf(retrievedSources == null ? List.of() : retrievedSources);
        explicitCitations = List.copyOf(explicitCitations == null ? List.of() : explicitCitations);
        retrievedSourceIdentities = List.copyOf(retrievedSourceIdentities == null ? List.of() : retrievedSourceIdentities);
        explicitCitationIdentities = List.copyOf(explicitCitationIdentities == null ? List.of() : explicitCitationIdentities);
    }

    public List<String> sourceSnapshotIdentities() {
        return explicitCitationIdentities;
    }

    public Map<String, Object> finalizeIdentitySnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("retrievedSourceIdentities", retrievedSourceIdentities);
        snapshot.put("explicitCitationIdentities", explicitCitationIdentities);
        snapshot.put("sourceSnapshotIdentities", explicitCitationIdentities);
        snapshot.put("retrievedSourceReferenceCount", retrievedSources.size());
        snapshot.put("sourceSnapshotReferenceCount", explicitCitations.size());
        return snapshot;
    }
}
