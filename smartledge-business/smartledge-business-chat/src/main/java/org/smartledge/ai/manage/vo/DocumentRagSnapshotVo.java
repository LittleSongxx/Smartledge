package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.knowledge.augmentation.model.raptor.RaptorQualityReport;

import java.util.List;
import java.util.Map;

/**
 * @description: 文档 RAG 学习快照
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRagSnapshotVo {

    private Long documentId;

    private String documentName;

    private Long parseTaskId;

    private Long indexTaskId;

    private Long planId;

    private List<MetricItem> metrics;

    private List<PipelineStageItem> pipelineStages;

    private ParserTraceItem parserTrace;

    private List<ParseBlockItem> parseBlocks;

    private List<StructureNodeItem> structureNodes;

    private List<ParentBlockItem> parentBlocks;

    private List<ChunkItem> chunks;

    private List<TableItem> tables;

    private List<PageOverlayItem> pageOverlays;

    private ArtifactGraphItem artifactGraph;

    private List<KgEntityItem> kgEntities;

    private List<KgRelationItem> kgRelations;

    private List<KgCommunityItem> kgCommunities;

    private KgGraphItem kgGraph;

    private List<RaptorNodeItem> raptorNodes;

    private RaptorTreeItem raptorTree;

    private RaptorQualityReport raptorQuality;

    private List<DocumentTaskLogVo> buildLogs;

    private String runtimeObservationNote;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricItem {

        private String label;

        private String value;

        private String hint;

        private String tone;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineStageItem {

        private String code;

        private String title;

        private String statusText;

        private String description;

        private Long count;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParserTraceItem {

        private String providerName;

        private String providerVersion;

        private String jobId;

        private Integer pageCount;

        private Integer ocrPageCount;

        private Integer blockCount;

        private Integer rawLayoutCount;

        private Integer tableCount;

        private Integer figureCount;

        private Integer captionCount;

        private Integer bboxBlockCount;

        private Double bboxBlockCoverage;

        private Integer tableCellCount;

        private Integer tableCellBboxCount;

        private Double tableCellBboxCoverage;

        private Integer warningCount;

        private Integer pollCount;

        private Integer submitElapsedMs;

        private Integer pollElapsedMs;

        private Integer resultFetchElapsedMs;

        private Integer standardizeElapsedMs;

        private Integer elapsedMs;

        private Map<String, Object> blockTypeCounts;

        private List<String> warnings;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseBlockItem {

        private Long blockId;

        private Integer blockNo;

        private String blockType;

        private String sectionPath;

        private Integer pageNo;

        private String pageRange;

        private String bboxJson;

        private String textPreview;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructureNodeItem {

        private Long nodeId;

        private Integer nodeNo;

        private Integer nodeType;

        private Integer depth;

        private String nodeCode;

        private String title;

        private String sectionPath;

        private String anchorText;

        private String syntaxSchemaVersion;

        private String syntaxSourceSha256;

        private String syntaxNodeId;

        private String syntaxNodeType;

        private String syntaxSourceOrigin;

        private Integer sourceStartByte;

        private Integer sourceEndByte;

        private Integer sourceStartLine;

        private Integer sourceStartColumn;

        private Integer sourceEndLine;

        private Integer sourceEndColumn;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParentBlockItem {

        private Long parentBlockId;

        private Integer parentNo;

        private String sectionPath;

        private Integer childCount;

        private Integer startChunkNo;

        private Integer endChunkNo;

        private String pageRange;

        private String textPreview;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkItem {

        private Long chunkId;

        private Long parentBlockId;

        private Integer chunkNo;

        private String sectionPath;

        private String chunkType;

        private String title;

        private String keywords;

        private String questions;

        private Integer vectorStatus;

        private String vectorStatusName;

        private Integer tokenCount;

        private String pageRange;

        private String sourceBlockIds;

        private String textPreview;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableItem {

        private Long tableId;

        private Integer tableNo;

        private String title;

        private String sectionPath;

        private Integer pageNo;

        private String pageRange;

        private Integer rowCount;

        private Integer columnCount;

        private String bboxJson;

        private List<TableColumnItem> columns;

        private List<TableRowItem> rows;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableColumnItem {

        private Long columnId;

        private Integer columnNo;

        private String columnName;

        private String normalizedName;

        private String valueType;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableRowItem {

        private Long rowId;

        private Integer rowNo;

        private String rowText;

        private List<String> cells;

        private List<TableCellItem> cellItems;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableCellItem {

        private Long cellId;

        private Long columnId;

        private Integer rowNo;

        private Integer columnNo;

        private String cellText;

        private Integer sourceRowNo;

        private Integer sourceColumnNo;

        private String sourceCellRef;

        private String bboxJson;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageOverlayItem {

        private Integer pageNo;

        private String displayPageNo;

        private Long pageImageArtifactId;

        private String pageImageObjectName;

        private Double pageWidth;

        private Double pageHeight;

        private List<PageOverlayRegionItem> overlays;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageOverlayRegionItem {

        private String overlayId;

        private String sourceType;

        private String type;

        private Long sourceId;

        private Integer sourceNo;

        private String label;

        private Integer pageNo;

        private String bboxJson;

        private Double x;

        private Double y;

        private Double width;

        private Double height;

        private Double leftRatio;

        private Double topRatio;

        private Double widthRatio;

        private Double heightRatio;

        private String sectionPath;

        private String textPreview;

        private String source;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArtifactGraphItem {

        private List<MetricItem> metrics;

        private List<ArtifactGraphNodeItem> nodes;

        private List<ArtifactGraphEdgeItem> edges;

        private List<ArtifactGraphTypeStatItem> typeStats;

        private String loadMode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArtifactGraphTypeStatItem {

        private String nodeType;

        private String label;

        private Long totalCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArtifactGraphNodeItem {

        private String nodeId;

        private String nodeType;

        private Long sourceId;

        private Integer sourceNo;

        private String label;

        private String subtitle;

        private String sectionPath;

        private String pageRange;

        private Integer pageNo;

        private String overlayId;

        private String textPreview;

        private String tone;

        private Integer childCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArtifactGraphEdgeItem {

        private String edgeId;

        private String sourceNodeId;

        private String targetNodeId;

        private String edgeType;

        private String label;

        private String detail;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgEntityItem {

        private Long entityId;

        private String name;

        private String entityType;

        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgRelationItem {

        private Long relationId;

        private Long sourceEntityId;

        private String sourceName;

        private Long targetEntityId;

        private String targetName;

        private String relationType;

        private String description;

        private String weight;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgCommunityItem {

        private Long communityId;

        private Integer communityNo;

        private String title;

        private String summary;

        private String entityIdsJson;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgGraphItem {

        private List<MetricItem> metrics;

        private List<KgGraphNodeItem> nodes;

        private List<KgGraphEdgeItem> edges;

        private List<KgGraphCommunityItem> communities;

        private List<KgGraphEvidenceItem> evidences;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgGraphNodeItem {

        private String nodeId;

        private Long entityId;

        private String name;

        private String entityType;

        private String description;

        private Long communityId;

        private Double pagerank;

        private Integer rankPosition;

        private Integer degree;

        private Integer inDegree;

        private Integer outDegree;

        private Double weightedDegree;

        private Double confidence;

        private Integer evidenceCount;

        private Integer chunkCount;

        private String qualityLevel;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgGraphEdgeItem {

        private String edgeId;

        private Long relationId;

        private String sourceNodeId;

        private String targetNodeId;

        private Long sourceEntityId;

        private Long targetEntityId;

        private String relationType;

        private String description;

        private String weight;

        private Integer evidenceCount;

        private String qualityLevel;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgGraphCommunityItem {

        private Long communityId;

        private Integer communityNo;

        private String title;

        private String summary;

        private Integer entityCount;

        private Integer relationCount;

        private Integer evidenceCount;

        private String rankScore;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgGraphEvidenceItem {

        private Long evidenceId;

        private Long entityId;

        private Long relationId;

        private Long chunkId;

        private Long parentBlockId;

        private Integer pageNo;

        private String pageRange;

        private String bboxJson;

        private String sectionPath;

        private String quoteText;

        private String sourceType;

        private Boolean grounded;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RaptorNodeItem {

        private Long nodeId;

        private String nodeKey;

        private Long parentNodeId;

        private Integer nodeLevel;

        private Integer nodeNo;

        private String title;

        private String summary;

        private String summaryWithWeight;

        private String sourceChunkIdsJson;

        private String sourceParentBlockIdsJson;

        private List<Long> sourceChunkIds;

        private List<Long> sourceParentBlockIds;

        private Integer sourceChunkCount;

        private Integer sourceParentBlockCount;

        private List<Long> childNodeIds;

        private Integer childNodeCount;

        private String sectionPath;

        private String pageRange;

        private String keywords;

        private String questions;

        private String scopeType;

        private String scopeKey;

        private Double qualityScore;

        private String qualityLevel;

        private String qualityRisk;

        private String summaryStrategy;

        private String clusterMethod;

        private String treeBuilderMethod;

        private Double avgClusterSize;

        private Integer maxClusterSizeObserved;

        private Integer singletonClusterCount;

        private Double levelCompressionRatio;

        private Double avgIntraClusterSimilarity;

        private Double treeBalanceScore;

        private Boolean abstractive;

        private String llmSummaryStatus;

        private Integer treeDepth;

        private String treePath;

        private List<RaptorSourceChunkItem> sourceChunks;

        private List<RaptorSourceParentBlockItem> sourceParentBlocks;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RaptorTreeItem {

        private List<MetricItem> metrics;

        private List<RaptorTreeNodeItem> roots;

        private List<RaptorTreeNodeItem> nodes;

        private List<RaptorTreeEdgeItem> edges;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RaptorTreeNodeItem {

        private String nodeId;

        private Long raptorNodeId;

        private Long parentNodeId;

        private String parentTreeNodeId;

        private Integer nodeLevel;

        private Integer nodeNo;

        private String title;

        private String summary;

        private Double qualityScore;

        private String qualityLevel;

        private String qualityRisk;

        private String scopeType;

        private String scopeKey;

        private String summaryStrategy;

        private String llmSummaryStatus;

        private Integer treeDepth;

        private String treePath;

        private Integer sourceChunkCount;

        private Integer sourceParentBlockCount;

        private Integer childNodeCount;

        private List<RaptorSourceChunkItem> sourceChunks;

        private List<RaptorSourceParentBlockItem> sourceParentBlocks;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RaptorTreeEdgeItem {

        private String edgeId;

        private String sourceNodeId;

        private String targetNodeId;

        private String edgeType;

        private String label;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RaptorSourceChunkItem {

        private Long chunkId;

        private Long parentBlockId;

        private Integer chunkNo;

        private String sectionPath;

        private String title;

        private String pageRange;

        private String textPreview;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RaptorSourceParentBlockItem {

        private Long parentBlockId;

        private Integer parentNo;

        private String sectionPath;

        private Integer childCount;

        private String pageRange;

        private String textPreview;
    }
}
