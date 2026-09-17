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
public class DocumentTableQuery {

    private Long tableId;

    private Operation operation;

    private String metricColumn;

    private String groupByColumn;

    @Builder.Default
    private List<String> selectedColumns = new ArrayList<>();

    @Builder.Default
    private List<Filter> filters = new ArrayList<>();

    public enum Operation {
        LOOKUP,
        COUNT,
        SUM,
        MAX,
        MIN,
        GROUP_COUNT,
        GROUP_SUM
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Filter {

        private String column;

        private Operator operator;

        private String value;
    }

    public enum Operator {
        EQ,
        CONTAINS,
        GT,
        GTE,
        LT,
        LTE
    }
}
