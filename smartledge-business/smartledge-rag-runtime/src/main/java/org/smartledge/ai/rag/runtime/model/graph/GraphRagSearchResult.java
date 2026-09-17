package org.smartledge.ai.rag.runtime.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagSearchResult {

    private Long documentId;

    private Long taskId;

    private Long entityId;

    private String entityName;

    private String canonicalEntityKey;

    private String canonicalEntityName;

    private Integer canonicalEntityCount;

    private Integer canonicalDocumentCount;

    private Long relationId;

    private String relationType;

    private String relationGroupKey;

    private Integer relationGroupRelationCount;

    private Integer relationGroupEvidenceCount;

    private Integer relationGroupDocumentCount;

    private Double kgQualityScore;

    private String kgQualityReasons;

    private String kgNoiseReasons;

    private Double kgPagerank;

    private Integer kgRankPosition;

    private Integer kgDegree;

    private Long relatedEntityId;

    private String relatedEntityName;

    private Long evidenceId;

    private Long communityId;

    private String communityTitle;

    private String communitySummary;

    private String crossDocumentCommunityKey;

    private Integer crossDocumentCommunityEntityCount;

    private Integer crossDocumentCommunityRelationGroupCount;

    private Integer crossDocumentCommunityEvidenceCount;

    private Integer crossDocumentCommunityDocumentCount;

    private Double kgCommunityRankScore;

    private String kgCommunityRankReasons;

    private Long chunkId;

    private Long parentBlockId;

    private String quoteText;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String sectionPath;

    private String graphPath;

    private Integer hopCount;

    private String queryPlanSource;

    private String queryPlanAnswerTypes;

    private String queryPlanEntities;

    private Long nHopSeedEntityId;

    private String nHopSeedEntityName;

    private String nHopPath;

    private Double rankBoost;

    private double score;
}
