package org.smartledge.ai.chatagent.rag.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Plan-owned authorization for entity-based evidence applicability.
 *
 * <p>Entity suggestions remain visible for audit, but only an explicitly authorized exclusion
 * may reject Source evidence.</p>
 */
public record EvidenceApplicabilityPlan(
    Authorization authorization,
    List<String> targetEntities,
    List<String> excludedEntities,
    String source,
    String reason
) {

    public enum Authorization {
        ADVISORY,
        AUTHORIZED_EXCLUSION
    }

    public EvidenceApplicabilityPlan {
        authorization = authorization == null ? Authorization.ADVISORY : authorization;
        targetEntities = normalizedCopy(targetEntities);
        excludedEntities = normalizedCopy(excludedEntities);
        source = Objects.toString(source, "");
        reason = Objects.toString(reason, "");
        if (authorization == Authorization.AUTHORIZED_EXCLUSION
            && (targetEntities.isEmpty() || excludedEntities.isEmpty())) {
            throw new IllegalArgumentException("Authorized evidence exclusion requires target and excluded entities");
        }
    }

    public static EvidenceApplicabilityPlan advisory(List<String> targets,
                                                      List<String> excluded,
                                                      String source,
                                                      String reason) {
        return new EvidenceApplicabilityPlan(Authorization.ADVISORY, targets, excluded, source, reason);
    }

    public static EvidenceApplicabilityPlan authorizedExclusion(List<String> targets,
                                                                 List<String> excluded,
                                                                 String source,
                                                                 String reason) {
        return new EvidenceApplicabilityPlan(Authorization.AUTHORIZED_EXCLUSION, targets, excluded, source, reason);
    }

    public boolean allowsExclusion() {
        return authorization == Authorization.AUTHORIZED_EXCLUSION;
    }

    private static List<String> normalizedCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .forEach(normalized::add);
        return List.copyOf(normalized);
    }
}
