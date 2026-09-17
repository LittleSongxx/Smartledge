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
public class GraphRagEvaluationReport {

    private String suiteId;

    private String name;

    private String scenario;

    private String question;

    private String sourceDocument;

    private Long documentId;

    private Long taskId;

    private Double passThreshold;

    private Boolean passed;

    private String evaluationLevel;

    private Double evaluationScore;

    private String summary;

    private GraphRagQualityReport qualityReport;

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

    @Builder.Default
    private List<String> observedExtractorSources = new ArrayList<>();

    @Builder.Default
    private List<ExtractorSourceStat> extractorSourceStats = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> llmExtractionAdvisor = new LinkedHashMap<>();

    @Builder.Default
    private List<EntityResult> entityResults = new ArrayList<>();

    @Builder.Default
    private List<RelationResult> relationResults = new ArrayList<>();

    @Builder.Default
    private List<ForbiddenRelationResult> forbiddenRelationResults = new ArrayList<>();

    @Builder.Default
    private List<EvidenceResult> evidenceResults = new ArrayList<>();

    public static GraphRagEvaluationReport empty(Long documentId, Long taskId, GraphRagQualityReport qualityReport) {
        return empty(null, null, documentId, taskId, qualityReport);
    }

    public static GraphRagEvaluationReport empty(String suiteId,
                                                 String name,
                                                 Long documentId,
                                                 Long taskId,
                                                 GraphRagQualityReport qualityReport) {
        return GraphRagEvaluationReport.builder()
            .suiteId(suiteId)
            .name(name)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.85D)
            .passed(false)
            .evaluationLevel(GraphRagQualityReport.LEVEL_EMPTY)
            .evaluationScore(0D)
            .summary("未配置 GraphRAG 评测期望项。")
            .qualityReport(qualityReport)
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
            .observedExtractorSources(List.of())
            .extractorSourceStats(List.of())
            .llmExtractionAdvisor(Map.of())
            .entityResults(List.of())
            .relationResults(List.of())
            .forbiddenRelationResults(List.of())
            .evidenceResults(List.of())
            .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityResult {

        private String expectedName;

        private String expectedEntityType;

        private Boolean required;

        private Boolean matched;

        private Long actualEntityId;

        private String actualName;

        @Builder.Default
        private List<String> missingAliases = new ArrayList<>();

        @Builder.Default
        private List<String> actualCandidateSources = new ArrayList<>();

        @Builder.Default
        private List<String> actualExtractorSources = new ArrayList<>();

        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationResult {

        private String expectedSourceName;

        private String expectedTargetName;

        private String expectedRelationType;

        private Boolean required;

        private Boolean matched;

        private Long actualRelationId;

        private Long actualSourceEntityId;

        private String actualSourceName;

        private Long actualTargetEntityId;

        private String actualTargetName;

        @Builder.Default
        private List<String> actualCandidateSources = new ArrayList<>();

        @Builder.Default
        private List<String> actualExtractorSources = new ArrayList<>();

        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForbiddenRelationResult {

        private String forbiddenSourceName;

        private String forbiddenTargetName;

        private String forbiddenRelationType;

        private Boolean violated;

        private Long actualRelationId;

        private Long actualSourceEntityId;

        private String actualSourceName;

        private Long actualTargetEntityId;

        private String actualTargetName;

        @Builder.Default
        private List<String> actualCandidateSources = new ArrayList<>();

        @Builder.Default
        private List<String> actualExtractorSources = new ArrayList<>();

        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceResult {

        private String expectedQuoteText;

        @Builder.Default
        private List<String> expectedQuoteKeywords = new ArrayList<>();

        private String expectedEntityName;

        private String expectedSourceName;

        private String expectedTargetName;

        private String expectedRelationType;

        private Boolean required;

        private Boolean matched;

        private Long actualEvidenceId;

        private Long actualEntityId;

        private Long actualRelationId;

        private Long actualChunkId;

        private String actualQuoteText;

        private Integer actualPageNo;

        private String actualPageRange;

        private String actualSectionPath;

        @Builder.Default
        private List<String> actualExtractorSources = new ArrayList<>();

        private String actualSourceType;

        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractorSourceStat {

        private String source;

        private Long entityCount;

        private Long relationCount;

        private Long evidenceCount;

        private Long totalCount;
    }
}
