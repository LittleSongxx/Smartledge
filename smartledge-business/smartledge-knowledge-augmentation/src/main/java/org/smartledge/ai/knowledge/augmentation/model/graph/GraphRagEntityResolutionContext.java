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
public class GraphRagEntityResolutionContext {

    @Builder.Default
    private List<EntityItem> entities = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityItem {

        private String sourceEntityId;

        private String name;

        private String normalizedName;

        private String entityType;

        @Builder.Default
        private List<String> aliases = new ArrayList<>();

        private String description;

        private Double confidence;

        @Builder.Default
        private List<Long> sourceChunkIds = new ArrayList<>();

        @Builder.Default
        private List<String> evidenceIds = new ArrayList<>();
    }
}
