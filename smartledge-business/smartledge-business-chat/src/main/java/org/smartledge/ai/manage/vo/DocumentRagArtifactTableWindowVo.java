package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRagArtifactTableWindowVo {

    private Long documentId;

    private Long parseTaskId;

    private Long indexTaskId;

    private String tableNodeId;

    private Long totalRows;

    private Long totalColumns;

    private Integer pageNo;

    private Integer pageSize;

    private Integer columnOffset;

    private Integer columnLimit;

    private List<ColumnItem> columns;

    private List<RowItem> records;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnItem {

        private Long columnId;

        private Integer columnNo;

        private String columnName;

        private String valueType;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowItem {

        private Long rowId;

        private Integer rowNo;

        private String rowText;

        private List<CellItem> cells;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CellItem {

        private Long cellId;

        private Long rowId;

        private Long columnId;

        private Integer rowNo;

        private Integer columnNo;

        private String cellText;

        private BigDecimal numericValue;

        private String sourceCellRef;

        private String bboxJson;
    }
}
