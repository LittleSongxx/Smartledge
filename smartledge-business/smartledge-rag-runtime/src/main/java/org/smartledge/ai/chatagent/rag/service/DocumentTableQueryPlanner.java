package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.rag.model.DocumentTableQueryPlanAdvice;
import org.smartledge.ai.chatagent.rag.model.DocumentTableQueryPlan;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableDescriptor;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 结构化表格 query plan 规划器。
 *
 * <p>结构化表格查询计划只由受控 {@link DocumentTableQueryPlanAdvisor} 输出，
 * 并由 Java 做 schema/字段/operator/filter 白名单校验后产出。不使用自然语言关键词（合计/求和/按/分组/为=…）
 * 在本地推断聚合、分组或过滤，避免形成与 BM25/vector 竞争的第二套语义解析。advisor 不可用或未产出通过校验的
 * 安全计划时，不生成结构化表格查询；表格内容仍可作为普通 chunk/BM25/vector 证据被召回。</p>
 */
@Component
@Slf4j
public class DocumentTableQueryPlanner {

    private static final double ADVISOR_CONFIDENCE_THRESHOLD = 0.72D;
    private static final int ADVISOR_FILTER_LIMIT = 6;
    private static final int ADVISOR_SELECTED_COLUMN_LIMIT = 12;

    private final DocumentTableQueryPlanAdvisor planAdvisor;

    public DocumentTableQueryPlanner() {
        this.planAdvisor = null;
    }

    @Autowired
    public DocumentTableQueryPlanner(ObjectProvider<DocumentTableQueryPlanAdvisor> planAdvisorProvider) {
        this(planAdvisorProvider == null ? null : planAdvisorProvider.getIfAvailable());
    }

    public DocumentTableQueryPlanner(DocumentTableQueryPlanAdvisor planAdvisor) {
        this.planAdvisor = planAdvisor;
    }

    public Optional<DocumentTableQueryPlan> plan(String question, List<DocumentTableDescriptor> tables) {
        if (StrUtil.isBlank(question) || tables == null || tables.isEmpty()) {
            return Optional.empty();
        }
        // 只走受控 advisor + schema 校验；advisor 缺失或抛错即拒绝结构化表格计划，不回退到自然语言关键词推断。
        if (planAdvisor == null) {
            return Optional.empty();
        }
        Optional<DocumentTableQueryPlanAdvice> advice;
        try {
            advice = planAdvisor.advise(question, tables);
        }
        catch (RuntimeException exception) {
            log.warn("表格查询受控计划 advisor 失败，planner 拒绝本次表格计划: question='{}', message={}",
                question,
                exception.getMessage());
            return Optional.empty();
        }
        return advice == null
            ? Optional.empty()
            : advice.flatMap(value -> validateAdvice(question, tables, value));
    }

    private Optional<DocumentTableQueryPlan> validateAdvice(String question,
                                                           List<DocumentTableDescriptor> tables,
                                                           DocumentTableQueryPlanAdvice advice) {
        if (advice == null || !Boolean.TRUE.equals(advice.getQueryTable())) {
            return Optional.empty();
        }
        double confidence = normalizeConfidence(advice.getConfidence());
        if (confidence < ADVISOR_CONFIDENCE_THRESHOLD) {
            return Optional.empty();
        }
        DocumentTableDescriptor table = findTable(tables, advice.getTableId()).orElse(null);
        if (table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return Optional.empty();
        }
        DocumentTableQuery.Operation operation = parseOperation(advice.getOperation()).orElse(null);
        if (operation == null) {
            return Optional.empty();
        }
        Optional<DocumentTableDescriptor.Column> metricColumn = matchColumn(table, advice.getMetricColumn());
        Optional<DocumentTableDescriptor.Column> groupColumn = matchColumn(table, advice.getGroupByColumn());
        boolean metricColumnAdvised = StrUtil.isNotBlank(advice.getMetricColumn());
        boolean groupColumnAdvised = StrUtil.isNotBlank(advice.getGroupByColumn());
        if ((metricColumnAdvised && metricColumn.isEmpty())
            || (groupColumnAdvised && groupColumn.isEmpty())) {
            return Optional.empty();
        }

        if (operation == DocumentTableQuery.Operation.COUNT && groupColumn.isPresent()) {
            operation = DocumentTableQuery.Operation.GROUP_COUNT;
        }
        if (operation == DocumentTableQuery.Operation.SUM && groupColumn.isPresent()) {
            operation = DocumentTableQuery.Operation.GROUP_SUM;
        }
        if (!requiresMetricColumn(operation) && metricColumnAdvised) {
            return Optional.empty();
        }
        if (requiresMetricColumn(operation) && (metricColumn.isEmpty() || !isNumberColumn(metricColumn.get()))) {
            return Optional.empty();
        }
        if (requiresGroupColumn(operation) && groupColumn.isEmpty()) {
            return Optional.empty();
        }
        if (!requiresGroupColumn(operation) && groupColumnAdvised) {
            return Optional.empty();
        }
        if (metricColumn.isPresent() && groupColumn.isPresent()
            && StrUtil.equals(metricColumn.get().getColumnName(), groupColumn.get().getColumnName())) {
            return Optional.empty();
        }

        Optional<List<DocumentTableQuery.Filter>> filters = validateFilters(table, advice.getFilters());
        if (filters.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<String>> selectedColumns = validateSelectedColumns(table, advice.getSelectedColumns());
        if (selectedColumns.isEmpty()
            || (operation != DocumentTableQuery.Operation.LOOKUP && !selectedColumns.get().isEmpty())) {
            return Optional.empty();
        }

        DocumentTableQuery query = DocumentTableQuery.builder()
            .tableId(table.getTableId())
            .operation(operation)
            .metricColumn(metricColumn.map(DocumentTableDescriptor.Column::getColumnName).orElse(null))
            .groupByColumn(groupColumn.map(DocumentTableDescriptor.Column::getColumnName).orElse(null))
            .selectedColumns(selectedColumns.get())
            .filters(filters.get())
            .build();
        return Optional.of(DocumentTableQueryPlan.builder()
            .table(table)
            .query(query)
            .reason(buildAdvisorReason(operation, metricColumn.orElse(null), groupColumn.orElse(null), filters.get(), confidence, advice.getReason()))
            .build());
    }

    private Optional<List<DocumentTableQuery.Filter>> validateFilters(DocumentTableDescriptor table,
                                                                     List<DocumentTableQueryPlanAdvice.FilterAdvice> filterAdviceList) {
        List<DocumentTableQueryPlanAdvice.FilterAdvice> rawFilters = filterAdviceList == null ? List.of() : filterAdviceList;
        if (rawFilters.size() > ADVISOR_FILTER_LIMIT) {
            return Optional.empty();
        }
        Map<String, DocumentTableQuery.Filter> filters = new LinkedHashMap<>();
        for (DocumentTableQueryPlanAdvice.FilterAdvice filterAdvice : rawFilters) {
            if (filterAdvice == null) {
                return Optional.empty();
            }
            DocumentTableDescriptor.Column column = matchColumn(table, filterAdvice.getColumn()).orElse(null);
            DocumentTableQuery.Operator operator = parseOperator(filterAdvice.getOperator()).orElse(null);
            String value = StrUtil.blankToDefault(filterAdvice.getValue(), "").trim();
            if (column == null || operator == null || StrUtil.isBlank(value)) {
                return Optional.empty();
            }
            if (value.length() > 120 || !operatorAllowedForColumn(operator, column)) {
                return Optional.empty();
            }
            filters.put(column.getColumnName(), DocumentTableQuery.Filter.builder()
                .column(column.getColumnName())
                .operator(operator)
                .value(value)
                .build());
        }
        return Optional.of(new ArrayList<>(filters.values()));
    }

    private Optional<List<String>> validateSelectedColumns(DocumentTableDescriptor table,
                                                           List<String> advisedColumns) {
        List<String> rawColumns = advisedColumns == null ? List.of() : advisedColumns;
        if (rawColumns.size() > ADVISOR_SELECTED_COLUMN_LIMIT) {
            return Optional.empty();
        }
        Map<String, String> selectedColumns = new LinkedHashMap<>();
        for (String advisedColumn : rawColumns) {
            DocumentTableDescriptor.Column column = matchColumn(table, advisedColumn).orElse(null);
            if (column == null) {
                return Optional.empty();
            }
            selectedColumns.put(normalize(column.getColumnName()), column.getColumnName());
        }
        return Optional.of(List.copyOf(selectedColumns.values()));
    }

    private boolean operatorAllowedForColumn(DocumentTableQuery.Operator operator,
                                             DocumentTableDescriptor.Column column) {
        if (operator == DocumentTableQuery.Operator.GT
            || operator == DocumentTableQuery.Operator.GTE
            || operator == DocumentTableQuery.Operator.LT
            || operator == DocumentTableQuery.Operator.LTE) {
            return isNumberColumn(column);
        }
        if (operator == DocumentTableQuery.Operator.CONTAINS) {
            return !isNumberColumn(column);
        }
        return true;
    }

    private Optional<DocumentTableDescriptor> findTable(List<DocumentTableDescriptor> tables, Long tableId) {
        if (tableId == null) {
            return Optional.empty();
        }
        return tables.stream()
            .filter(table -> table != null && StrUtil.equals(String.valueOf(tableId), String.valueOf(table.getTableId())))
            .findFirst();
    }

    private Optional<DocumentTableDescriptor.Column> matchColumn(DocumentTableDescriptor table, String columnName) {
        String normalized = normalize(columnName);
        if (normalized.isBlank() || table.getColumns() == null) {
            return Optional.empty();
        }
        return table.getColumns().stream()
            .filter(column -> normalized.equals(normalize(column.getColumnName()))
                || normalized.equals(normalize(column.getNormalizedName())))
            .findFirst();
    }

    private Optional<DocumentTableQuery.Operation> parseOperation(String operation) {
        String normalized = StrUtil.blankToDefault(operation, "")
            .trim()
            .replace('-', '_')
            .toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DocumentTableQuery.Operation.valueOf(normalized));
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<DocumentTableQuery.Operator> parseOperator(String operator) {
        String normalized = StrUtil.blankToDefault(operator, "")
            .trim()
            .replace('-', '_')
            .toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DocumentTableQuery.Operator.valueOf(normalized));
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean isNumberColumn(DocumentTableDescriptor.Column column) {
        return column != null && "NUMBER".equalsIgnoreCase(StrUtil.blankToDefault(column.getValueType(), ""));
    }

    private boolean requiresMetricColumn(DocumentTableQuery.Operation operation) {
        return operation == DocumentTableQuery.Operation.SUM
            || operation == DocumentTableQuery.Operation.MAX
            || operation == DocumentTableQuery.Operation.MIN
            || operation == DocumentTableQuery.Operation.GROUP_SUM;
    }

    private boolean requiresGroupColumn(DocumentTableQuery.Operation operation) {
        return operation == DocumentTableQuery.Operation.GROUP_COUNT
            || operation == DocumentTableQuery.Operation.GROUP_SUM;
    }

    private double normalizeConfidence(Double confidence) {
        if (confidence == null || !Double.isFinite(confidence)) {
            return 0D;
        }
        if (confidence > 1D) {
            return Math.min(confidence / 100D, 1D);
        }
        return Math.max(confidence, 0D);
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s`*_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String buildReason(DocumentTableQuery.Operation operation,
                               DocumentTableDescriptor.Column metricColumn,
                               DocumentTableDescriptor.Column groupColumn,
                               List<DocumentTableQuery.Filter> filters) {
        List<String> parts = new ArrayList<>();
        parts.add("operation=" + operation.name());
        if (metricColumn != null) {
            parts.add("metric=" + metricColumn.getColumnName());
        }
        if (groupColumn != null) {
            parts.add("groupBy=" + groupColumn.getColumnName());
        }
        if (filters != null && !filters.isEmpty()) {
            parts.add("filters=" + filters.size());
        }
        return String.join(", ", parts);
    }

    private String buildAdvisorReason(DocumentTableQuery.Operation operation,
                                      DocumentTableDescriptor.Column metricColumn,
                                      DocumentTableDescriptor.Column groupColumn,
                                      List<DocumentTableQuery.Filter> filters,
                                      double confidence,
                                      String reason) {
        String baseReason = buildReason(operation, metricColumn, groupColumn, filters);
        List<String> parts = new ArrayList<>();
        parts.add("advisor");
        parts.add(baseReason);
        parts.add("confidence=" + String.format(Locale.ROOT, "%.2f", confidence));
        if (StrUtil.isNotBlank(reason)) {
            parts.add("reason=" + reason.trim());
        }
        return String.join(", ", parts);
    }
}
