package org.smartledge.ai.knowledge.augmentation.model.raptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaptorQualityReport {

    public static final String LEVEL_EMPTY = "EMPTY";

    public static final String LEVEL_STRONG = "STRONG";

    public static final String LEVEL_WATCH = "WATCH";

    public static final String LEVEL_WEAK = "WEAK";

    private String qualityLevel;

    private Double configuredQualityFloor;

    private Double recommendedQualityFloor;

    private Double averageQualityScore;

    private Double minQualityScore;

    private Double p10QualityScore;

    private Double medianQualityScore;

    private Double p90QualityScore;

    private Long nodeCount;

    private Long abstractiveNodeCount;

    private Double abstractiveCoverage;

    private Long lowQualityNodeCount;

    private Double lowQualityRatio;

    private Long watchNodeCount;

    private Double watchRatio;

    private Long highQualityNodeCount;

    private Long floorBlockedNodeCount;

    private Double floorBlockedRatio;

    private Double averageClusterSize;

    private Integer maxClusterSizeObserved;

    private Long singletonClusterCount;

    private Double averageLevelCompressionRatio;

    private Double averageIntraClusterSimilarity;

    private Double averageTreeBalanceScore;

    private String summary;

    private List<String> tuningSuggestions;

    private List<LevelBucket> levelBuckets;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LevelBucket {

        private Integer level;

        private Long nodeCount;

        private Double averageQualityScore;

        private Double minQualityScore;

        private Double maxQualityScore;
    }
}
