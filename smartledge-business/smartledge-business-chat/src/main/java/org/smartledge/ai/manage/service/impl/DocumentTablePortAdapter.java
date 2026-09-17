package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.manage.service.DocumentTableStructureService;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableDescriptor;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableQuery;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableQueryResult;
import org.smartledge.ai.rag.runtime.port.DocumentTablePort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class DocumentTablePortAdapter implements DocumentTablePort {

    private final DocumentTableStructureService delegate;

    public DocumentTablePortAdapter(DocumentTableStructureService delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<DocumentTableDescriptor> listTables(List<Long> documentIds, List<Long> indexTaskIds) {
        List<org.smartledge.ai.manage.model.table.DocumentTableDescriptor> source = delegate.listTables(documentIds, indexTaskIds);
        if (source == null) return List.of();
        return source.stream().map(item -> DocumentTableDescriptor.builder()
            .tableId(item.getTableId())
            .documentId(item.getDocumentId())
            .indexTaskId(item.getIndexTaskId())
            .taskId(item.getTaskId())
            .blockId(item.getBlockId())
            .tableNo(item.getTableNo())
            .title(item.getTitle())
            .sectionPath(item.getSectionPath())
            .pageNo(item.getPageNo())
            .pageRange(item.getPageRange())
            .bboxJson(item.getBboxJson())
            .rowCount(item.getRowCount())
            .columnCount(item.getColumnCount())
            .columns(item.getColumns() == null ? List.of() : item.getColumns().stream()
                .map(column -> DocumentTableDescriptor.Column.builder()
                    .columnNo(column.getColumnNo())
                    .columnName(column.getColumnName())
                    .normalizedName(column.getNormalizedName())
                    .valueType(column.getValueType())
                    .build())
                .toList())
            .build())
            .toList();
    }

    @Override
    public DocumentTableQueryResult query(DocumentTableQuery query) {
        if (query == null) return null;
        org.smartledge.ai.manage.model.table.DocumentTableQuery source = org.smartledge.ai.manage.model.table.DocumentTableQuery.builder()
            .tableId(query.getTableId())
            .operation(toManage(query.getOperation()))
            .metricColumn(query.getMetricColumn())
            .groupByColumn(query.getGroupByColumn())
            .selectedColumns(copyList(query.getSelectedColumns()))
            .filters(query.getFilters() == null ? List.of() : query.getFilters().stream()
                .map(filter -> org.smartledge.ai.manage.model.table.DocumentTableQuery.Filter.builder()
                    .column(filter.getColumn())
                    .operator(toManage(filter.getOperator()))
                    .value(filter.getValue())
                    .build())
                .toList())
            .build();
        return fromManage(delegate.query(source));
    }

    private static org.smartledge.ai.manage.model.table.DocumentTableQuery.Operation toManage(DocumentTableQuery.Operation operation) {
        return operation == null ? null : org.smartledge.ai.manage.model.table.DocumentTableQuery.Operation.valueOf(operation.name());
    }

    private static org.smartledge.ai.manage.model.table.DocumentTableQuery.Operator toManage(DocumentTableQuery.Operator operator) {
        return operator == null ? null : org.smartledge.ai.manage.model.table.DocumentTableQuery.Operator.valueOf(operator.name());
    }

    private static DocumentTableQueryResult fromManage(org.smartledge.ai.manage.model.table.DocumentTableQueryResult source) {
        if (source == null) return null;
        return DocumentTableQueryResult.builder()
            .tableId(source.getTableId())
            .documentId(source.getDocumentId())
            .taskId(source.getTaskId())
            .blockId(source.getBlockId())
            .tableNo(source.getTableNo())
            .tableTitle(source.getTableTitle())
            .sectionPath(source.getSectionPath())
            .pageNo(source.getPageNo())
            .pageRange(source.getPageRange())
            .bboxJson(source.getBboxJson())
            .operation(source.getOperation())
            .value(source.getValue())
            .groupedValues(copyMap(source.getGroupedValues()))
            .matchedRowCount(source.getMatchedRowCount())
            .evidenceRowIds(copyList(source.getEvidenceRowIds()))
            .evidenceRowNos(copyList(source.getEvidenceRowNos()))
            .evidenceColumnIds(copyList(source.getEvidenceColumnIds()))
            .evidenceColumnNos(copyList(source.getEvidenceColumnNos()))
            .evidenceColumnNames(copyList(source.getEvidenceColumnNames()))
            .evidenceCellIds(copyList(source.getEvidenceCellIds()))
            .evidenceCellCoordinates(copyList(source.getEvidenceCellCoordinates()))
            .evidenceCellBboxJsons(copyList(source.getEvidenceCellBboxJsons()))
            .evidenceText(source.getEvidenceText())
            .build();
    }

    private static <T> List<T> copyList(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private static <K, V> LinkedHashMap<K, V> copyMap(java.util.Map<K, V> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
