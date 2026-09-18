package org.smartledge.ai.knowledge.augmentation.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagQualityReport {

    public static final String LEVEL_EMPTY = "EMPTY";

    public static final String LEVEL_WEAK = "WEAK";

    public static final String LEVEL_WATCH = "WATCH";

    public static final String LEVEL_STRONG = "STRONG";

    private Long documentId;

    private Long taskId;

    private String qualityLevel;

    private Double qualityScore;

    private String summary;

    private Long entityCount;

    private Long relationCount;

    private Long evidenceCount;

    private Long groundedEntityCount;

    private Long groundedRelationCount;

    private Long traceableEvidenceCount;

    private Long rankedGraphItemCount;

    private Long controlledExtractionItemCount;

    private Long entityResolutionEnhancedCount;

    private Double entityEvidenceCoverage;

    private Double relationEvidenceCoverage;

    private Double evidenceTraceabilityCoverage;

    private Double rankCoverage;

    @Builder.Default
    private List<SignalItem> signals = new ArrayList<>();

    public static GraphRagQualityReport empty(Long documentId, Long taskId) {
        return GraphRagQualityReport.builder()
            .documentId(documentId)
            .taskId(taskId)
            .qualityLevel(LEVEL_EMPTY)
            .qualityScore(0D)
            .summary("未生成 GraphRAG KG。")
            .entityCount(0L)
            .relationCount(0L)
            .evidenceCount(0L)
            .groundedEntityCount(0L)
            .groundedRelationCount(0L)
            .traceableEvidenceCount(0L)
            .rankedGraphItemCount(0L)
            .controlledExtractionItemCount(0L)
            .entityResolutionEnhancedCount(0L)
            .entityEvidenceCoverage(0D)
            .relationEvidenceCoverage(0D)
            .evidenceTraceabilityCoverage(0D)
            .rankCoverage(0D)
            .signals(List.of())
            .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SignalItem {

        private String label;

        private String value;

        private String hint;

        private String tone;
    }
}
