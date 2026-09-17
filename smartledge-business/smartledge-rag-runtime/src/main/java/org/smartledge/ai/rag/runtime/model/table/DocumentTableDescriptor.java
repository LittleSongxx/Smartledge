package org.smartledge.ai.rag.runtime.model.table;

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
public class DocumentTableDescriptor {

    private Long tableId;

    private Long documentId;

    /** Index task that froze this table's source parse task for the current retrieval revision. */
    private Long indexTaskId;

    /** Source parse task that owns the persisted table/block/row/cell artifacts. */
    private Long taskId;

    private Long blockId;

    private Integer tableNo;

    private String title;

    private String sectionPath;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private Integer rowCount;

    private Integer columnCount;

    @Builder.Default
    private List<Column> columns = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Column {

        private Integer columnNo;

        private String columnName;

        private String normalizedName;

        private String valueType;
    }
}
