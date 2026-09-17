package org.smartledge.ai.knowledge.augmentation.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagEvaluationBatchReport {

    private String batchId;

    private String name;

    private String evaluationLevel;

    private Double evaluationScore;

    private String summary;

    private Long suiteCount;

    private Long passedSuiteCount;

    private Long failedSuiteCount;

    private Long expectedEntityCount;

    private Long matchedEntityCount;

    private Long expectedRelationCount;

    private Long matchedRelationCount;

    private Long forbiddenRelationCount;

    private Long violatedForbiddenRelationCount;

    private Long expectedEvidenceCount;

    private Long matchedEvidenceCount;

    private Double entityRecall;

    private Double relationRecall;

    private Double relationPrecision;

    private Double evidenceRecall;

    private Double overallRecall;

    private Double passRate;

    private Double minSuiteRecall;

    private Double maxSuiteRecall;

    @Builder.Default
    private List<GraphRagEvaluationReport> reports = new ArrayList<>();

    @Builder.Default
    private Map<String, Long> llmExtractionAdvisorStatusCounts = new LinkedHashMap<>();

    @Builder.Default
    private List<String> llmExtractionAdvisorRejectedReasons = new ArrayList<>();

    @Builder.Default
    private List<FailedSuite> failedSuites = new ArrayList<>();

    public static GraphRagEvaluationBatchReport empty(String batchId, String name) {
        return GraphRagEvaluationBatchReport.builder()
            .batchId(batchId)
            .name(name)
            .evaluationLevel(GraphRagQualityReport.LEVEL_EMPTY)
            .evaluationScore(0D)
            .summary("未配置 GraphRAG 批量评测样例。")
            .suiteCount(0L)
            .passedSuiteCount(0L)
            .failedSuiteCount(0L)
            .expectedEntityCount(0L)
            .matchedEntityCount(0L)
            .expectedRelationCount(0L)
            .matchedRelationCount(0L)
            .forbiddenRelationCount(0L)
            .violatedForbiddenRelationCount(0L)
            .expectedEvidenceCount(0L)
            .matchedEvidenceCount(0L)
            .entityRecall(0D)
            .relationRecall(0D)
            .relationPrecision(1D)
            .evidenceRecall(0D)
            .overallRecall(0D)
            .passRate(0D)
            .minSuiteRecall(0D)
            .maxSuiteRecall(0D)
            .reports(List.of())
            .llmExtractionAdvisorStatusCounts(Map.of())
            .llmExtractionAdvisorRejectedReasons(List.of())
            .failedSuites(List.of())
            .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedSuite {

        private String suiteId;

        private String name;

        private String sourceDocument;

        private Long documentId;

        private Long taskId;

        private Double overallRecall;

        private String evaluationLevel;

        private String reason;

        @Builder.Default
        private List<String> observedExtractorSources = new ArrayList<>();

        @Builder.Default
        private Map<String, Object> llmExtractionAdvisor = new LinkedHashMap<>();

        @Builder.Default
        private List<String> missingEntityNames = new ArrayList<>();

        @Builder.Default
        private List<String> missingRelationNames = new ArrayList<>();

        @Builder.Default
        private List<String> forbiddenRelationViolations = new ArrayList<>();

        @Builder.Default
        private List<String> missingEvidenceHints = new ArrayList<>();
    }
}
