package org.smartledge.ai.knowledge.augmentation.model.es;

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
public class RaptorSummaryIndexRecord {

    private Long nodeId;

    private Long documentId;

    private Long taskId;

    private String scopeType;

    private String scopeKey;

    private Long parentNodeId;

    private Integer nodeLevel;

    private Integer nodeNo;

    private String title;

    private String summary;

    private String summaryWithWeight;

    private String sectionPath;

    private String pageRange;

    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    @Builder.Default
    private List<String> questions = new ArrayList<>();

    @Builder.Default
    private List<Long> sourceChunkIds = new ArrayList<>();

    @Builder.Default
    private List<Long> sourceParentBlockIds = new ArrayList<>();

    @Builder.Default
    private List<Long> sourceDocumentIds = new ArrayList<>();

    @Builder.Default
    private List<Long> sourceTaskIds = new ArrayList<>();

    private Double qualityScore;

    private String summaryStrategy;
}
