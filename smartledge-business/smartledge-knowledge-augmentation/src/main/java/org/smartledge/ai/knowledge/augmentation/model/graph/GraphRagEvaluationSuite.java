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
public class GraphRagEvaluationSuite {

    private String suiteId;

    private String name;

    private String scenario;

    private String question;

    private String sourceDocument;

    private Long documentId;

    private Long taskId;

    private Double passThreshold;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    private List<ExpectedEntity> expectedEntities = new ArrayList<>();

    @Builder.Default
    private List<ExpectedRelation> expectedRelations = new ArrayList<>();

    @Builder.Default
    private List<ForbiddenRelation> forbiddenRelations = new ArrayList<>();

    @Builder.Default
    private List<ExpectedEvidence> expectedEvidences = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpectedEntity {

        private String name;

        private String entityType;

        @Builder.Default
        private List<String> aliases = new ArrayList<>();

        @Builder.Default
        private List<String> mustHaveAliases = new ArrayList<>();

        private Boolean required;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpectedRelation {

        private String sourceName;

        private String targetName;

        private String relationType;

        @Builder.Default
        private List<String> relationTypeAliases = new ArrayList<>();

        private Boolean required;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForbiddenRelation {

        private String sourceName;

        private String targetName;

        private String relationType;

        @Builder.Default
        private List<String> relationTypeAliases = new ArrayList<>();

        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpectedEvidence {

        private String quoteText;

        private String entityName;

        private String sourceName;

        private String targetName;

        private String relationType;

        @Builder.Default
        private List<String> relationTypeAliases = new ArrayList<>();

        @Builder.Default
        private List<String> quoteKeywords = new ArrayList<>();

        private Boolean required;
    }
}
