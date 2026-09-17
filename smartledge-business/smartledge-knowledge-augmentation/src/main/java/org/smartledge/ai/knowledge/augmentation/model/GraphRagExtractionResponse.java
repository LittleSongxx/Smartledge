package org.smartledge.ai.knowledge.augmentation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagExtractionResponse {

    private List<Entity> entities = new ArrayList<>();

    private List<Relation> relations = new ArrayList<>();

    private List<Evidence> evidences = new ArrayList<>();

    private List<Community> communities = new ArrayList<>();

    private Map<String, Object> metadata;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entity {

        private String id;

        private String name;

        private String normalizedName;

        private List<String> aliases = new ArrayList<>();

        private String type;

        private String description;

        private Double confidence;

        private List<Long> sourceChunkIds = new ArrayList<>();

        private List<String> evidenceIds = new ArrayList<>();

        private Map<String, Object> metadata;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Relation {

        private String id;

        private String sourceEntityId;

        private String targetEntityId;

        private String relationType;

        private String description;

        private Double weight;

        private Double confidence;

        private List<String> evidenceIds = new ArrayList<>();

        private Map<String, Object> metadata;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Evidence {

        private String id;

        private String entityId;

        private String relationId;

        private Long chunkId;

        private Long parentBlockId;

        private String quoteText;

        private Integer pageNo;

        private String pageRange;

        private String bboxJson;

        private String sectionPath;

        private Map<String, Object> metadata;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Community {

        private String id;

        private String title;

        private String summary;

        private List<String> entityIds = new ArrayList<>();

        private List<String> relationIds = new ArrayList<>();

        private List<String> evidenceIds = new ArrayList<>();

        private Map<String, Object> metadata;
    }
}
