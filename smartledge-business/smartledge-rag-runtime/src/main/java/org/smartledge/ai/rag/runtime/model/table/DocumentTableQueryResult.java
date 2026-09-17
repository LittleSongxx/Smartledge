package org.smartledge.ai.rag.runtime.model.table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTableQueryResult {

    private Long tableId;

    private Long documentId;

    private Long taskId;

    private Long blockId;

    private Integer tableNo;

    private String tableTitle;

    private String sectionPath;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String operation;

    private BigDecimal value;

    @Builder.Default
    private Map<String, BigDecimal> groupedValues = new LinkedHashMap<>();

    private int matchedRowCount;

    @Builder.Default
    private List<Long> evidenceRowIds = new ArrayList<>();

    @Builder.Default
    private List<Integer> evidenceRowNos = new ArrayList<>();

    @Builder.Default
    private List<Long> evidenceColumnIds = new ArrayList<>();

    @Builder.Default
    private List<Integer> evidenceColumnNos = new ArrayList<>();

    @Builder.Default
    private List<String> evidenceColumnNames = new ArrayList<>();

    @Builder.Default
    private List<Long> evidenceCellIds = new ArrayList<>();

    @Builder.Default
    private List<String> evidenceCellCoordinates = new ArrayList<>();

    @Builder.Default
    private List<String> evidenceCellBboxJsons = new ArrayList<>();

    private String evidenceText;
}
