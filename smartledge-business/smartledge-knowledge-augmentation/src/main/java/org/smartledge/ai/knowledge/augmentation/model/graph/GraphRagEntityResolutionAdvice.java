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
public class GraphRagEntityResolutionAdvice {

    private Boolean resolvable;

    @Builder.Default
    private List<MergeGroup> mergeGroups = new ArrayList<>();

    private Double confidence;

    private String reason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergeGroup {

        @Builder.Default
        private List<String> entityIds = new ArrayList<>();

        private String canonicalName;

        @Builder.Default
        private List<String> aliases = new ArrayList<>();

        private Double confidence;

        private String reason;
    }
}
