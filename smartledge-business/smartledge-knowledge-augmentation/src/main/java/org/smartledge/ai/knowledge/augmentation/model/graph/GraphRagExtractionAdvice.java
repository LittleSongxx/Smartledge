package org.smartledge.ai.knowledge.augmentation.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagExtractionAdvice {

    public static final double MIN_ACCEPTED_CONFIDENCE = 0.70D;

    private Boolean graphable;

    @Builder.Default
    private List<EntityItem> entities = new ArrayList<>();

    @Builder.Default
    private List<RelationItem> relations = new ArrayList<>();

    @Builder.Default
    private List<EvidenceItem> evidences = new ArrayList<>();

    private Double confidence;

    private String reason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityItem {

        private String id;

        private String name;

        private String normalizedName;

        private String entityType;

        @Builder.Default
        private List<String> aliases = new ArrayList<>();

        private String description;

        private Double confidence;

        @Builder.Default
        private List<Long> sourceChunkIds = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationItem {

        private String id;

        private String sourceEntityId;

        private String targetEntityId;

        private String relationType;

        private String supportMode;

        private String predicateQuoteText;

        private Long tableId;

        private Integer rowNo;

        private Integer sourceColumnNo;

        private Integer targetColumnNo;

        private String relationTypeReason;

        private String description;

        private Double weight;

        private Double confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceItem {

        private String id;

        private String entityId;

        private String relationId;

        private Long chunkId;

        private String quoteText;

        private Map<String, Object> metadata;

        private Double confidence;
    }
}
