package org.smartledge.ai.chatagent.rag.model;

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
public class DocumentTableQueryPlanAdvice {

    private Boolean queryTable;

    private Long tableId;

    private String operation;

    private String metricColumn;

    private String groupByColumn;

    @Builder.Default
    private List<String> selectedColumns = new ArrayList<>();

    @Builder.Default
    private List<FilterAdvice> filters = new ArrayList<>();

    private Double confidence;

    private String reason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterAdvice {

        private String column;

        private String operator;

        private String value;
    }
}
