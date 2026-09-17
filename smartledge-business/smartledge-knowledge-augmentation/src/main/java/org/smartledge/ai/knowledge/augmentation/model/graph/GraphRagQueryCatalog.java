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
public class GraphRagQueryCatalog {

    @Builder.Default
    private List<EntityItem> entities = new ArrayList<>();

    @Builder.Default
    private List<RelationItem> relations = new ArrayList<>();

    @Builder.Default
    private List<CommunityItem> communities = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityItem {

        private Long entityId;

        private String name;

        private String normalizedName;

        private String entityType;

        @Builder.Default
        private List<String> aliases = new ArrayList<>();

        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationItem {

        private Long relationId;

        private String relationType;

        private Long sourceEntityId;

        private String sourceEntityName;

        private Long targetEntityId;

        private String targetEntityName;

        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommunityItem {

        private Long communityId;

        private String title;

        private String summary;
    }
}
