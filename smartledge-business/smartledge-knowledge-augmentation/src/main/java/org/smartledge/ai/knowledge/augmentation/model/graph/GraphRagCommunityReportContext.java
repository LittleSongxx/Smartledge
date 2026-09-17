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
public class GraphRagCommunityReportContext {

    private Long communityId;

    private Integer communityNo;

    private String originalTitle;

    private String originalSummary;

    private Double rankBoost;

    @Builder.Default
    private List<EntityItem> entities = new ArrayList<>();

    @Builder.Default
    private List<RelationItem> relations = new ArrayList<>();

    @Builder.Default
    private List<EvidenceItem> evidences = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityItem {

        private Long entityId;

        private String name;

        private String entityType;

        private String description;

        private Double rankBoost;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationItem {

        private Long relationId;

        private Long sourceEntityId;

        private String sourceEntityName;

        private Long targetEntityId;

        private String targetEntityName;

        private String relationType;

        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceItem {

        private Long evidenceId;

        private Long entityId;

        private Long relationId;

        private Long chunkId;

        private String quoteText;

        private String sectionPath;
    }
}
