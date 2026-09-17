package org.smartledge.ai.manage.vo;

import java.util.List;

public record ExecutionFailureDiagnosticVo(
        String schemaVersion,
        String code,
        String category,
        String stage,
        String operation,
        String severity,
        String certainty,
        List<Issue> issues,
        Boolean retryable,
        Integer upstreamStatus,
        boolean truncated,
        Provenance provenance) {

    public record Issue(
            String ruleId,
            String message,
            List<Parameter> parameters,
            String constraint,
            List<String> suggestions) {
    }

    public record Parameter(
            String configKey,
            String label,
            String scope,
            String unit,
            Object configuredValue,
            Object effectiveValue,
            Object actualValue,
            String measurementKind,
            String effectiveMode,
            String effectiveModeLabel,
            Target target) {
    }

    public record Target(String type, String categoryKey, String configKey, Long knowledgeBaseId, String groupKey) {
    }

    public record Provenance(Long documentId, Long taskId, String batchId, String layer) {
    }
}
