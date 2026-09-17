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
public class GraphRagCommunityReportAdvice {

    private Boolean reportable;

    private String title;

    private String summary;

    @Builder.Default
    private List<Finding> findings = new ArrayList<>();

    @Builder.Default
    private List<Long> evidenceIds = new ArrayList<>();

    private Double rating;

    private String ratingExplanation;

    private Double confidence;

    private String reason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Finding {

        private String summary;

        private String explanation;

        @Builder.Default
        private List<Long> evidenceIds = new ArrayList<>();
    }
}
