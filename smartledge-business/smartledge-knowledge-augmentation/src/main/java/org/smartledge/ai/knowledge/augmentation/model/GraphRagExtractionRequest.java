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
public class GraphRagExtractionRequest {

    private String schemaVersion = "graph-candidates.v3";
    private String operation = "plan";
    private Long sourceParseTaskId;
    private Long planId;
    private List<Long> excludedBlankChunkIds = new ArrayList<>();
    private String inputFingerprint;
    private String configurationFingerprint;
    private String planFingerprint;
    private String batchId;
    private Long budgetMillis;
    private Map<String, Object> options;
    private List<Map<String, Object>> segments = new ArrayList<>();

    private Long documentId;

    private Long taskId;

    private List<Chunk> chunks = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Chunk {

        private Long chunkId;

        private Long parentBlockId;

        private Integer chunkNo;

        private String chunkType;

        private String title;

        private String sectionPath;

        private Integer pageNo;

        private String pageRange;

        private String bboxJson;

        private String text;

        private String contentWithWeight;

        private String sourceBlockIds;

        private List<StructuredTable> structuredTables = new ArrayList<>();

        private Map<String, Object> metadata;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredTable {
        private Long tableId;
        private Long blockId;
        private Integer tableNo;
        private String title;
        private List<StructuredTableColumn> columns = new ArrayList<>();
        private List<StructuredTableRow> rows = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredTableColumn {
        private Integer columnNo;
        private String columnName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredTableRow {
        private Integer rowNo;
        private String rowText;
        private List<StructuredTableCell> cells = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredTableCell {
        private Integer columnNo;
        private String text;
    }
}
