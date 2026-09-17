package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocumentBlock;
import org.smartledge.ai.manage.data.SuperAgentDocumentTable;
import org.smartledge.ai.manage.data.SuperAgentDocumentTableCell;
import org.smartledge.ai.manage.data.SuperAgentDocumentTableColumn;
import org.smartledge.ai.manage.data.SuperAgentDocumentTableRow;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableCellMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableColumnMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableRowMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.smartledge.ai.manage.model.table.DocumentTableDescriptor;
import org.smartledge.ai.manage.model.table.DocumentTableQuery;
import org.smartledge.ai.manage.model.table.DocumentTableQueryResult;
import org.smartledge.ai.manage.service.DocumentTableStructureService;
import org.smartledge.ai.manage.support.DocumentTableCandidate;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
@Slf4j
public class DocumentTableStructureServiceImpl implements DocumentTableStructureService {

    private static final int MAX_EVIDENCE_ROWS = 20;
    private static final int MAX_EVIDENCE_COLUMNS = 12;
    private static final int MAX_EVIDENCE_CELLS = 80;

    private final SuperAgentDocumentTableMapper tableMapper;
    private final SuperAgentDocumentTableColumnMapper columnMapper;
    private final SuperAgentDocumentTableRowMapper rowMapper;
    private final SuperAgentDocumentTableCellMapper cellMapper;
    private final SuperAgentDocumentTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final UidGenerator uidGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceTaskTables(Long documentId,
                                  Long taskId,
                                  List<SuperAgentDocumentBlock> blockList,
                                  List<DocumentTableCandidate> tableCandidates) {
        deleteByTask(documentId, taskId);
        if (documentId == null || taskId == null || CollUtil.isEmpty(tableCandidates)) {
            return;
        }
        Map<Integer, SuperAgentDocumentBlock> blocksByNo = (blockList == null ? List.<SuperAgentDocumentBlock>of() : blockList).stream()
            .filter(block -> block.getBlockNo() != null)
            .collect(Collectors.toMap(
                SuperAgentDocumentBlock::getBlockNo,
                block -> block,
                (left, right) -> {
                    throw new IllegalStateException("document blockNo 重复: " + left.getBlockNo());
                },
                LinkedHashMap::new
            ));
        int tableNo = 1;
        for (DocumentTableCandidate candidate : tableCandidates) {
            if (candidate == null || candidate.sourceBlockNo() == null) {
                throw new IllegalStateException("table candidate 缺少 sourceBlockNo");
            }
            SuperAgentDocumentBlock block = blocksByNo.get(candidate.sourceBlockNo());
            if (block == null || !Objects.equals(block.getDocumentId(), documentId) || !Objects.equals(block.getTaskId(), taskId)) {
                throw new IllegalStateException("table candidate 无法绑定当前 task block: blockNo=" + candidate.sourceBlockNo());
            }
            saveTable(documentId, taskId, block, tableNo++, candidate);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentTableDescriptor> listTables(List<Long> documentIds, List<Long> indexTaskIds) {
        if (CollUtil.isEmpty(documentIds) && CollUtil.isEmpty(indexTaskIds)) {
            return List.of();
        }
        List<TableRetrievalScope> scopes = resolveTableRetrievalScopes(documentIds, indexTaskIds);
        Map<TableArtifactScope, TableRetrievalScope> scopeByArtifact = scopes.stream()
            .collect(Collectors.toMap(
                scope -> new TableArtifactScope(scope.documentId(), scope.sourceParseTaskId()),
                scope -> scope,
                (left, right) -> {
                    throw new IllegalStateException("同一文档解析版本绑定了多个检索 index task");
                },
                LinkedHashMap::new
            ));
        List<Long> sourceParseTaskIds = scopes.stream()
            .map(TableRetrievalScope::sourceParseTaskId)
            .distinct()
            .toList();
        LambdaQueryWrapper<SuperAgentDocumentTable> queryWrapper = new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .in(SuperAgentDocumentTable::getDocumentId, documentIds)
            .in(SuperAgentDocumentTable::getTaskId, sourceParseTaskIds)
            .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentTable::getTaskId)
            .orderByAsc(SuperAgentDocumentTable::getTableNo);
        List<SuperAgentDocumentTable> tables = tableMapper.selectList(queryWrapper).stream()
            .filter(table -> scopeByArtifact.containsKey(new TableArtifactScope(table.getDocumentId(), table.getTaskId())))
            .toList();
        if (tables.isEmpty()) {
            return List.of();
        }

        List<Long> tableIds = tables.stream().map(SuperAgentDocumentTable::getId).toList();
        Map<Long, List<SuperAgentDocumentTableColumn>> columnsByTableId = columnMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentTableColumn>()
                    .in(SuperAgentDocumentTableColumn::getTableId, tableIds)
                    .eq(SuperAgentDocumentTableColumn::getStatus, BusinessStatus.YES.getCode())
                    .orderByAsc(SuperAgentDocumentTableColumn::getTableId)
                    .orderByAsc(SuperAgentDocumentTableColumn::getColumnNo)
            ).stream()
            .collect(Collectors.groupingBy(SuperAgentDocumentTableColumn::getTableId, LinkedHashMap::new, Collectors.toList()));

        return tables.stream()
            .map(table -> {
                TableRetrievalScope scope = scopeByArtifact.get(new TableArtifactScope(table.getDocumentId(), table.getTaskId()));
                return toDescriptor(table, scope.indexTaskId(), columnsByTableId.getOrDefault(table.getId(), List.of()));
            })
            .toList();
    }

    private List<TableRetrievalScope> resolveTableRetrievalScopes(List<Long> documentIds,
                                                                  List<Long> indexTaskIds) {
        if (CollUtil.isEmpty(documentIds) || CollUtil.isEmpty(indexTaskIds) || documentIds.size() != indexTaskIds.size()) {
            throw new IllegalArgumentException("table retrieval document/index task scope 必须非空且一一对应");
        }
        List<Long> distinctIndexTaskIds = indexTaskIds.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        Map<Long, SuperAgentDocumentTask> indexTasks = taskMapper.selectBatchIds(distinctIndexTaskIds).stream()
            .collect(Collectors.toMap(SuperAgentDocumentTask::getId, task -> task, (left, right) -> left, LinkedHashMap::new));

        List<PendingTableRetrievalScope> pending = new ArrayList<>(documentIds.size());
        for (int index = 0; index < documentIds.size(); index++) {
            Long documentId = documentIds.get(index);
            Long indexTaskId = indexTaskIds.get(index);
            SuperAgentDocumentTask indexTask = indexTasks.get(indexTaskId);
            if (!validIndexTask(indexTask, documentId)) {
                throw invalidLineage(documentId, indexTaskId, "index task 不存在、未成功或不属于当前文档");
            }
            Long sourceParseTaskId = indexTask.getSourceParseTaskId();
            if (sourceParseTaskId == null) {
                throw invalidLineage(documentId, indexTaskId, "index task 缺少冻结 sourceParseTaskId");
            }
            pending.add(new PendingTableRetrievalScope(documentId, indexTaskId, sourceParseTaskId));
        }

        List<Long> sourceParseTaskIds = pending.stream()
            .map(PendingTableRetrievalScope::sourceParseTaskId)
            .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        Map<Long, SuperAgentDocumentTask> parseTasks = taskMapper.selectBatchIds(sourceParseTaskIds).stream()
            .collect(Collectors.toMap(SuperAgentDocumentTask::getId, task -> task, (left, right) -> left, LinkedHashMap::new));

        List<TableRetrievalScope> resolved = new ArrayList<>(pending.size());
        for (PendingTableRetrievalScope scope : pending) {
            SuperAgentDocumentTask parseTask = parseTasks.get(scope.sourceParseTaskId());
            if (!validSourceParseTask(parseTask, scope.documentId())) {
                throw invalidLineage(scope.documentId(), scope.indexTaskId(), "source parse task 不存在、未成功或不属于当前文档");
            }
            resolved.add(new TableRetrievalScope(scope.documentId(), scope.indexTaskId(), scope.sourceParseTaskId()));
        }
        return List.copyOf(resolved);
    }

    private boolean validIndexTask(SuperAgentDocumentTask task, Long documentId) {
        return task != null
            && Objects.equals(task.getDocumentId(), documentId)
            && Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.BUILD_INDEX.getCode())
            && Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())
            && Objects.equals(task.getStatus(), BusinessStatus.YES.getCode());
    }

    private boolean validSourceParseTask(SuperAgentDocumentTask task, Long documentId) {
        return task != null
            && Objects.equals(task.getDocumentId(), documentId)
            && Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())
            && Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())
            && Objects.equals(task.getStatus(), BusinessStatus.YES.getCode());
    }

    private IllegalStateException invalidLineage(Long documentId, Long indexTaskId, String reason) {
        log.warn("table retrieval task lineage 拒绝: documentId={}, indexTaskId={}, reason={}",
            documentId, indexTaskId, reason);
        return new IllegalStateException("table retrieval task lineage invalid: " + reason);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentTableQueryResult query(DocumentTableQuery query) {
        validateQuery(query);
        SuperAgentDocumentTable table = requiredTable(query.getTableId());
        List<SuperAgentDocumentTableColumn> columns = listColumns(query.getTableId());
        List<SuperAgentDocumentTableRow> rows = listRows(query.getTableId());
        List<SuperAgentDocumentTableCell> cells = listCells(query.getTableId());
        Map<Integer, SuperAgentDocumentTableColumn> columnByNo = columns.stream()
            .collect(Collectors.toMap(SuperAgentDocumentTableColumn::getColumnNo, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, SuperAgentDocumentTableColumn> columnByName = columns.stream()
            .collect(Collectors.toMap(item -> item.getNormalizedName(), item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<Integer, List<SuperAgentDocumentTableCell>> cellsByRowNo = cells.stream()
            .collect(Collectors.groupingBy(SuperAgentDocumentTableCell::getRowNo, LinkedHashMap::new, Collectors.toList()));

        List<RowView> matchedRows = rows.stream()
            .map(row -> toRowView(row, cellsByRowNo.getOrDefault(row.getRowNo(), List.of()), columnByNo))
            .filter(row -> matchesFilters(row, query.getFilters(), columnByName))
            .toList();

        BigDecimal value = BigDecimal.ZERO;
        Map<String, BigDecimal> groupedValues = new LinkedHashMap<>();
        if (query.getOperation() == DocumentTableQuery.Operation.COUNT) {
            value = BigDecimal.valueOf(matchedRows.size());
        }
        else if (query.getOperation() == DocumentTableQuery.Operation.SUM) {
            value = sumRows(matchedRows, requiredColumn(columnByName, query.getMetricColumn()));
        }
        else if (query.getOperation() == DocumentTableQuery.Operation.MAX) {
            value = maxRows(matchedRows, requiredColumn(columnByName, query.getMetricColumn()));
        }
        else if (query.getOperation() == DocumentTableQuery.Operation.MIN) {
            value = minRows(matchedRows, requiredColumn(columnByName, query.getMetricColumn()));
        }
        else if (query.getOperation() == DocumentTableQuery.Operation.GROUP_COUNT) {
            SuperAgentDocumentTableColumn groupColumn = requiredColumn(columnByName, query.getGroupByColumn());
            for (RowView row : matchedRows) {
                String group = StrUtil.blankToDefault(row.value(groupColumn.getColumnNo()), "(空)");
                groupedValues.merge(group, BigDecimal.ONE, BigDecimal::add);
            }
        }
        else if (query.getOperation() == DocumentTableQuery.Operation.GROUP_SUM) {
            SuperAgentDocumentTableColumn groupColumn = requiredColumn(columnByName, query.getGroupByColumn());
            SuperAgentDocumentTableColumn metricColumn = requiredColumn(columnByName, query.getMetricColumn());
            for (RowView row : matchedRows) {
                String group = StrUtil.blankToDefault(row.value(groupColumn.getColumnNo()), "(空)");
                groupedValues.merge(group, row.numeric(metricColumn.getColumnNo()).orElse(BigDecimal.ZERO), BigDecimal::add);
            }
        }
        List<SuperAgentDocumentTableColumn> evidenceColumns = resolveEvidenceColumns(query, columns, columnByName);
        int evidenceRowLimit = resolveEvidenceRowLimit(evidenceColumns.size());
        List<RowView> evidenceRows = matchedRows.stream()
            .sorted(Comparator.comparing(RowView::rowNo))
            .limit(evidenceRowLimit)
            .toList();
        TableEvidenceLocation evidenceLocation = buildEvidenceLocation(evidenceRows, evidenceColumns);

        return DocumentTableQueryResult.builder()
            .tableId(query.getTableId())
            .documentId(table.getDocumentId())
            .taskId(table.getTaskId())
            .blockId(table.getBlockId())
            .tableNo(table.getTableNo())
            .tableTitle(table.getTitle())
            .sectionPath(table.getSectionPath())
            .pageNo(table.getPageNo())
            .pageRange(table.getPageRange())
            .bboxJson(table.getBboxJson())
            .operation(query.getOperation().name())
            .value(value)
            .groupedValues(groupedValues)
            .matchedRowCount(matchedRows.size())
            .evidenceRowIds(evidenceLocation.rowIds())
            .evidenceRowNos(evidenceLocation.rowNos())
            .evidenceColumnIds(evidenceLocation.columnIds())
            .evidenceColumnNos(evidenceLocation.columnNos())
            .evidenceColumnNames(evidenceLocation.columnNames())
            .evidenceCellIds(evidenceLocation.cellIds())
            .evidenceCellCoordinates(evidenceLocation.cellCoordinates())
            .evidenceCellBboxJsons(evidenceLocation.cellBboxJsons())
            .evidenceText(renderEvidenceText(
                evidenceColumns,
                evidenceRows,
                matchedRows.size(),
                groupedValues,
                value,
                query.getOperation()
            ))
            .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        List<Long> tableIds = tableMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTable>()
                .eq(SuperAgentDocumentTable::getDocumentId, documentId)
                .eq(SuperAgentDocumentTable::getTaskId, taskId))
            .stream()
            .map(SuperAgentDocumentTable::getId)
            .toList();
        deleteByTableIds(tableIds);
        tableMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .eq(SuperAgentDocumentTable::getDocumentId, documentId)
            .eq(SuperAgentDocumentTable::getTaskId, taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        List<Long> tableIds = tableMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTable>()
                .eq(SuperAgentDocumentTable::getDocumentId, documentId))
            .stream()
            .map(SuperAgentDocumentTable::getId)
            .toList();
        deleteByTableIds(tableIds);
        tableMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .eq(SuperAgentDocumentTable::getDocumentId, documentId));
    }

    private void saveTable(Long documentId,
                           Long taskId,
                           SuperAgentDocumentBlock block,
                           int tableNo,
                           DocumentTableCandidate candidate) {
        List<DocumentTableCandidate.Row> rows = validateCandidateRows(candidate);
        DocumentTableCandidate.Row headerRow = rows.get(0);
        List<DocumentTableCandidate.Row> dataRows = List.copyOf(rows.subList(1, rows.size()));
        List<String> header = normalizeHeader(
            headerRow.cells().stream().map(cell -> StrUtil.blankToDefault(cell.text(), "").trim()).toList(),
            headerRow.cells().size()
        );

        Long tableId = uidGenerator.getUid();
        SuperAgentDocumentTable table = new SuperAgentDocumentTable();
        table.setId(tableId);
        table.setDocumentId(documentId);
        table.setTaskId(taskId);
        table.setBlockId(block.getId());
        table.setTableNo(tableNo);
        table.setSectionPath(firstNonBlank(candidate.sectionPath(), block.getSectionPath()));
        table.setPageNo(candidate.pageNo() == null ? block.getPageNo() : candidate.pageNo());
        table.setPageRange(firstNonBlank(candidate.pageRange(), block.getPageRange()));
        table.setBboxJson(firstNonBlank(candidate.bboxJson(), block.getBboxJson()));
        table.setTitle(resolveTableTitle(candidate, tableNo));
        table.setRowCount(dataRows.size());
        table.setColumnCount(header.size());
        table.setTableHtml(firstNonBlank(candidate.tableHtml(), block.getTableHtml()));
        table.setMetadataJson(writeTableMetadata(candidate, headerRow, header));
        table.setStatus(BusinessStatus.YES.getCode());
        tableMapper.insert(table);

        Map<Integer, Long> columnIdByNo = saveColumns(documentId, taskId, tableId, header, dataRows);
        saveRowsAndCells(documentId, taskId, tableId, dataRows, columnIdByNo);
    }

    private Map<Integer, Long> saveColumns(Long documentId,
                                           Long taskId,
                                           Long tableId,
                                           List<String> header,
                                           List<DocumentTableCandidate.Row> dataRows) {
        Map<Integer, Long> columnIdByNo = new LinkedHashMap<>();
        for (int columnIndex = 0; columnIndex < header.size(); columnIndex++) {
            int columnNo = columnIndex + 1;
            Long columnId = uidGenerator.getUid();
            columnIdByNo.put(columnNo, columnId);

            SuperAgentDocumentTableColumn column = new SuperAgentDocumentTableColumn();
            column.setId(columnId);
            column.setDocumentId(documentId);
            column.setTaskId(taskId);
            column.setTableId(tableId);
            column.setColumnNo(columnNo);
            column.setColumnName(header.get(columnIndex));
            column.setNormalizedName(normalizeName(header.get(columnIndex)));
            column.setValueType(resolveValueType(
                dataRows.stream().map(row -> row.cells().stream().map(DocumentTableCandidate.Cell::text).toList()).toList(),
                columnIndex
            ));
            column.setStatus(BusinessStatus.YES.getCode());
            columnMapper.insert(column);
        }
        return columnIdByNo;
    }

    private void saveRowsAndCells(Long documentId,
                                  Long taskId,
                                  Long tableId,
                                  List<DocumentTableCandidate.Row> dataRows,
                                  Map<Integer, Long> columnIdByNo) {
        for (int rowIndex = 0; rowIndex < dataRows.size(); rowIndex++) {
            int rowNo = rowIndex + 1;
            Long rowId = uidGenerator.getUid();
            DocumentTableCandidate.Row sourceRow = dataRows.get(rowIndex);
            List<DocumentTableCandidate.Cell> rowCells = sourceRow.cells();
            List<String> rowValues = rowCells.stream().map(cell -> StrUtil.blankToDefault(cell.text(), "")).toList();

            SuperAgentDocumentTableRow row = new SuperAgentDocumentTableRow();
            row.setId(rowId);
            row.setDocumentId(documentId);
            row.setTaskId(taskId);
            row.setTableId(tableId);
            row.setRowNo(rowNo);
            row.setRowText(String.join(" | ", rowValues));
            row.setStatus(BusinessStatus.YES.getCode());
            rowMapper.insert(row);

            for (int columnIndex = 0; columnIndex < rowCells.size(); columnIndex++) {
                int columnNo = columnIndex + 1;
                DocumentTableCandidate.Cell sourceCell = rowCells.get(columnIndex);
                String cellText = rowValues.get(columnIndex);
                SuperAgentDocumentTableCell cell = new SuperAgentDocumentTableCell();
                cell.setId(uidGenerator.getUid());
                cell.setDocumentId(documentId);
                cell.setTaskId(taskId);
                cell.setTableId(tableId);
                cell.setRowId(rowId);
                cell.setColumnId(columnIdByNo.get(columnNo));
                cell.setRowNo(rowNo);
                cell.setColumnNo(columnNo);
                cell.setCellText(cellText);
                cell.setNumericValue(parseDecimal(cellText).orElse(null));
                cell.setSourceRowNo(sourceCell.sourceRowNo() == null ? sourceRow.rowIndex() + 1 : sourceCell.sourceRowNo());
                cell.setSourceColumnNo(sourceCell.sourceColumnNo() == null ? columnNo : sourceCell.sourceColumnNo());
                cell.setSourceCellRef(firstNonBlank(
                    sourceCell.sourceCellRef(),
                    "R" + cell.getSourceRowNo() + "C" + cell.getSourceColumnNo()
                ));
                cell.setBboxJson(StrUtil.isBlank(sourceCell.bboxJson()) ? null : sourceCell.bboxJson());
                cell.setMetadataJson(writeCellMetadata(sourceRow, sourceCell));
                cell.setStatus(BusinessStatus.YES.getCode());
                cellMapper.insert(cell);
            }
        }
    }

    private List<DocumentTableCandidate.Row> validateCandidateRows(DocumentTableCandidate candidate) {
        if (candidate == null || candidate.rows() == null || candidate.rows().isEmpty()) {
            throw new IllegalStateException("table candidate 缺少 rows");
        }
        List<DocumentTableCandidate.Row> rows = candidate.rows();
        int columnCount = -1;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            DocumentTableCandidate.Row row = rows.get(rowIndex);
            if (row == null || !Objects.equals(row.rowIndex(), rowIndex) || row.header() != (rowIndex == 0)
                || row.cells() == null || row.cells().isEmpty()) {
                throw new IllegalStateException("table candidate row 顺序/header 非法: rowIndex=" + rowIndex);
            }
            if (columnCount < 0) {
                columnCount = row.cells().size();
            }
            else if (columnCount != row.cells().size()) {
                throw new IllegalStateException("table candidate columnCount 不守恒");
            }
            for (int columnIndex = 0; columnIndex < row.cells().size(); columnIndex++) {
                DocumentTableCandidate.Cell cell = row.cells().get(columnIndex);
                if (cell == null || !Objects.equals(cell.rowIndex(), rowIndex)
                    || !Objects.equals(cell.columnIndex(), columnIndex) || cell.header() != row.header()) {
                    throw new IllegalStateException("table candidate cell 顺序/header 非法: row=" + rowIndex
                        + ", column=" + columnIndex);
                }
            }
        }
        return rows;
    }

    private String writeTableMetadata(DocumentTableCandidate candidate,
                                      DocumentTableCandidate.Row headerRow,
                                      List<String> normalizedHeader) {
        Map<String, Object> values = new LinkedHashMap<>(candidate.sourceMetadata());
        values.put("projectionOwner", candidate.projectionOwner());
        putIfPresent(values, "syntaxSchemaVersion", candidate.schemaVersion());
        putIfPresent(values, "sourceOrigin", candidate.sourceOrigin());
        putIfPresent(values, "sourceSha256", candidate.sourceSha256());
        putIfPresent(values, "syntaxNodeId", candidate.syntaxNodeId());
        putIfPresent(values, "sourceSpan", spanMap(candidate.sourceSpan()));
        List<Map<String, Object>> columns = new ArrayList<>();
        for (int index = 0; index < headerRow.cells().size(); index++) {
            DocumentTableCandidate.Cell cell = headerRow.cells().get(index);
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("columnNo", index + 1);
            column.put("columnName", normalizedHeader.get(index));
            column.put("sourceText", StrUtil.blankToDefault(cell.text(), ""));
            putIfPresent(column, "alignment", cell.alignment());
            putIfPresent(column, "syntaxNodeId", cell.syntaxNodeId());
            putIfPresent(column, "sourceCellRef", cell.sourceCellRef());
            putIfPresent(column, "sourceSpan", spanMap(cell.sourceSpan()));
            columns.add(column);
        }
        values.put("columns", columns);
        return writeJson(values, "序列化表格 provenance 失败");
    }

    private String writeCellMetadata(DocumentTableCandidate.Row row, DocumentTableCandidate.Cell cell) {
        Map<String, Object> values = new LinkedHashMap<>(cell.sourceMetadata());
        values.put("rowIndex", cell.rowIndex());
        values.put("columnIndex", cell.columnIndex());
        values.put("header", cell.header());
        putIfPresent(values, "alignment", cell.alignment());
        putIfPresent(values, "sourceRowNo", cell.sourceRowNo());
        putIfPresent(values, "sourceColumnNo", cell.sourceColumnNo());
        putIfPresent(values, "sourceCellRef", cell.sourceCellRef());
        putIfPresent(values, "syntaxNodeId", cell.syntaxNodeId());
        putIfPresent(values, "sourceSpan", spanMap(cell.sourceSpan()));
        putIfPresent(values, "rowSyntaxNodeId", row.syntaxNodeId());
        putIfPresent(values, "rowSourceSpan", spanMap(row.sourceSpan()));
        return writeJson(values, "序列化表格单元格 provenance 失败");
    }

    private Map<String, Object> spanMap(DocumentTableCandidate.SourceSpan span) {
        if (span == null) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("startByte", span.startByte());
        values.put("endByte", span.endByte());
        values.put("startLine", span.startLine());
        values.put("startColumn", span.startColumn());
        values.put("endLine", span.endLine());
        values.put("endColumn", span.endColumn());
        return values;
    }

    private String writeJson(Map<String, Object> values, String message) {
        try {
            return objectMapper.writeValueAsString(values);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && StrUtil.isBlank(text)) {
            return;
        }
        values.put(key, value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private List<String> normalizeHeader(List<String> rawHeader, int columnCount) {
        List<String> header = padRow(rawHeader, columnCount);
        for (int index = 0; index < header.size(); index++) {
            if (StrUtil.isBlank(header.get(index))) {
                header.set(index, "列" + (index + 1));
            }
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int index = 0; index < header.size(); index++) {
            String name = header.get(index);
            int count = seen.merge(name, 1, Integer::sum);
            if (count > 1) {
                header.set(index, name + "_" + count);
            }
        }
        return header;
    }

    private List<String> padRow(List<String> row, int columnCount) {
        List<String> padded = new ArrayList<>(row == null ? List.of() : row);
        while (padded.size() < columnCount) {
            padded.add("");
        }
        if (padded.size() > columnCount) {
            return new ArrayList<>(padded.subList(0, columnCount));
        }
        return padded;
    }

    private String resolveValueType(List<List<String>> rows, int columnIndex) {
        long nonBlankCount = rows.stream()
            .map(row -> columnIndex < row.size() ? row.get(columnIndex) : "")
            .filter(StrUtil::isNotBlank)
            .count();
        long numericCount = rows.stream()
            .map(row -> columnIndex < row.size() ? row.get(columnIndex) : "")
            .filter(StrUtil::isNotBlank)
            .filter(value -> parseDecimal(value).isPresent())
            .count();
        return nonBlankCount > 0 && numericCount == nonBlankCount ? "NUMBER" : "TEXT";
    }

    private Optional<BigDecimal> parseDecimal(String value) {
        String normalized = StrUtil.blankToDefault(value, "")
            .replace(",", "")
            .replace("，", "")
            .replace("%", "")
            .trim();
        if (StrUtil.isBlank(normalized)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(normalized));
        }
        catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private List<SuperAgentDocumentTableColumn> listColumns(Long tableId) {
        return columnMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTableColumn>()
            .eq(SuperAgentDocumentTableColumn::getTableId, tableId)
            .eq(SuperAgentDocumentTableColumn::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentTableColumn::getColumnNo));
    }

    private List<SuperAgentDocumentTableRow> listRows(Long tableId) {
        return rowMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTableRow>()
            .eq(SuperAgentDocumentTableRow::getTableId, tableId)
            .eq(SuperAgentDocumentTableRow::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentTableRow::getRowNo));
    }

    private List<SuperAgentDocumentTableCell> listCells(Long tableId) {
        return cellMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTableCell>()
            .eq(SuperAgentDocumentTableCell::getTableId, tableId)
            .eq(SuperAgentDocumentTableCell::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentTableCell::getRowNo)
            .orderByAsc(SuperAgentDocumentTableCell::getColumnNo));
    }

    private RowView toRowView(SuperAgentDocumentTableRow row,
                              List<SuperAgentDocumentTableCell> cells,
                              Map<Integer, SuperAgentDocumentTableColumn> columnByNo) {
        Map<Integer, CellView> values = new LinkedHashMap<>();
        for (SuperAgentDocumentTableCell cell : cells) {
            SuperAgentDocumentTableColumn column = columnByNo.get(cell.getColumnNo());
            if (column == null) {
                continue;
            }
            values.put(cell.getColumnNo(), new CellView(
                cell.getId(),
                cell.getColumnId(),
                cell.getColumnNo(),
                column.getColumnName(),
                cell.getCellText(),
                cell.getNumericValue(),
                StrUtil.blankToDefault(cell.getSourceCellRef(), ""),
                StrUtil.blankToDefault(cell.getBboxJson(), "")
            ));
        }
        return new RowView(row.getId(), row.getRowNo(), values);
    }

    private boolean matchesFilters(RowView row,
                                   List<DocumentTableQuery.Filter> filters,
                                   Map<String, SuperAgentDocumentTableColumn> columnByName) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (DocumentTableQuery.Filter filter : filters) {
            SuperAgentDocumentTableColumn column = requiredColumn(columnByName, filter.getColumn());
            String cellValue = StrUtil.blankToDefault(row.value(column.getColumnNo()), "");
            BigDecimal numericValue = row.numeric(column.getColumnNo()).orElse(null);
            if (!matchesFilter(cellValue, numericValue, filter)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesFilter(String cellValue, BigDecimal numericValue, DocumentTableQuery.Filter filter) {
        DocumentTableQuery.Operator operator = filter.getOperator() == null ? DocumentTableQuery.Operator.EQ : filter.getOperator();
        String filterValue = StrUtil.blankToDefault(filter.getValue(), "");
        return switch (operator) {
            case EQ -> cellValue.equals(filterValue);
            case CONTAINS -> cellValue.contains(filterValue);
            case GT -> numericValue != null && parseDecimal(filterValue).map(value -> numericValue.compareTo(value) > 0).orElse(false);
            case GTE -> numericValue != null && parseDecimal(filterValue).map(value -> numericValue.compareTo(value) >= 0).orElse(false);
            case LT -> numericValue != null && parseDecimal(filterValue).map(value -> numericValue.compareTo(value) < 0).orElse(false);
            case LTE -> numericValue != null && parseDecimal(filterValue).map(value -> numericValue.compareTo(value) <= 0).orElse(false);
        };
    }

    private BigDecimal sumRows(List<RowView> rows, SuperAgentDocumentTableColumn column) {
        return rows.stream()
            .map(row -> row.numeric(column.getColumnNo()).orElse(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal maxRows(List<RowView> rows, SuperAgentDocumentTableColumn column) {
        return rows.stream()
            .map(row -> row.numeric(column.getColumnNo()))
            .flatMap(Optional::stream)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    }

    private BigDecimal minRows(List<RowView> rows, SuperAgentDocumentTableColumn column) {
        return rows.stream()
            .map(row -> row.numeric(column.getColumnNo()))
            .flatMap(Optional::stream)
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    }

    private SuperAgentDocumentTableColumn requiredColumn(Map<String, SuperAgentDocumentTableColumn> columnByName, String columnName) {
        SuperAgentDocumentTableColumn column = columnByName.get(normalizeName(columnName));
        if (column == null) {
            throw new IllegalArgumentException("未知表格列: " + columnName);
        }
        return column;
    }

    private String normalizeName(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s`*_\\-，,。；;：:（）()\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private void validateQuery(DocumentTableQuery query) {
        if (query == null || query.getTableId() == null) {
            throw new IllegalArgumentException("tableId 不能为空");
        }
        if (query.getOperation() == null) {
            throw new IllegalArgumentException("operation 不能为空");
        }
        if ((query.getOperation() == DocumentTableQuery.Operation.SUM
            || query.getOperation() == DocumentTableQuery.Operation.MAX
            || query.getOperation() == DocumentTableQuery.Operation.MIN
            || query.getOperation() == DocumentTableQuery.Operation.GROUP_SUM)
            && StrUtil.isBlank(query.getMetricColumn())) {
            throw new IllegalArgumentException("数值聚合查询必须指定 metricColumn");
        }
        if ((query.getOperation() == DocumentTableQuery.Operation.GROUP_COUNT || query.getOperation() == DocumentTableQuery.Operation.GROUP_SUM)
            && StrUtil.isBlank(query.getGroupByColumn())) {
            throw new IllegalArgumentException("group 查询必须指定 groupByColumn");
        }
        if (query.getOperation() != DocumentTableQuery.Operation.GROUP_COUNT
            && query.getOperation() != DocumentTableQuery.Operation.GROUP_SUM
            && StrUtil.isNotBlank(query.getGroupByColumn())) {
            throw new IllegalArgumentException("非 group 查询不得指定 groupByColumn");
        }
        if (query.getOperation() != DocumentTableQuery.Operation.SUM
            && query.getOperation() != DocumentTableQuery.Operation.MAX
            && query.getOperation() != DocumentTableQuery.Operation.MIN
            && query.getOperation() != DocumentTableQuery.Operation.GROUP_SUM
            && StrUtil.isNotBlank(query.getMetricColumn())) {
            throw new IllegalArgumentException("非数值聚合查询不得指定 metricColumn");
        }
        if (query.getOperation() != DocumentTableQuery.Operation.LOOKUP
            && query.getSelectedColumns() != null && !query.getSelectedColumns().isEmpty()) {
            throw new IllegalArgumentException("selectedColumns 只允许用于 LOOKUP");
        }
        if (query.getSelectedColumns() != null && query.getSelectedColumns().size() > MAX_EVIDENCE_COLUMNS) {
            throw new IllegalArgumentException("selectedColumns 超过安全上限");
        }
        if (query.getFilters() != null && query.getFilters().stream().anyMatch(filter -> filter == null
            || StrUtil.isBlank(filter.getColumn()) || filter.getOperator() == null || StrUtil.isBlank(filter.getValue()))) {
            throw new IllegalArgumentException("filter 必须包含 column/operator/value");
        }
    }

    private String resolveTableTitle(DocumentTableCandidate candidate, int tableNo) {
        if (StrUtil.isNotBlank(candidate.titleHint())) {
            if (StrUtil.isNotBlank(candidate.sectionPath())) {
                return candidate.sectionPath() + " / " + candidate.titleHint() + " 表格" + tableNo;
            }
            return candidate.titleHint() + " 表格" + tableNo;
        }
        if (StrUtil.isNotBlank(candidate.sectionPath())) {
            return candidate.sectionPath() + " 表格" + tableNo;
        }
        return "表格" + tableNo;
    }

    private String renderEvidenceText(List<SuperAgentDocumentTableColumn> columns,
                                      List<RowView> rows,
                                      int matchedRowCount,
                                      Map<String, BigDecimal> groupedValues,
                                      BigDecimal value,
                                      DocumentTableQuery.Operation operation) {
        StringBuilder builder = new StringBuilder();
        builder.append("表格查询结果：").append(operation.name()).append('\n');
        if (groupedValues != null && !groupedValues.isEmpty()) {
            groupedValues.forEach((group, groupValue) -> builder.append("- ").append(group).append(": ").append(groupValue).append('\n'));
        }
        else if (operation != DocumentTableQuery.Operation.LOOKUP) {
            builder.append("结果：").append(value == null ? BigDecimal.ZERO : value).append('\n');
        }
        builder.append("命中行数：").append(matchedRowCount).append('\n');
        builder.append("列：")
            .append(columns.stream().map(SuperAgentDocumentTableColumn::getColumnName).collect(Collectors.joining(" | ")))
            .append('\n');
        rows.stream()
            .sorted(Comparator.comparing(RowView::rowNo))
            .limit(MAX_EVIDENCE_ROWS)
            .forEach(row -> builder.append("行").append(row.rowNo()).append(": ").append(renderRow(columns, row)).append('\n'));
        return builder.toString().trim();
    }

    private int resolveEvidenceRowLimit(int evidenceColumnCount) {
        if (evidenceColumnCount <= 0) {
            return MAX_EVIDENCE_ROWS;
        }
        return Math.min(MAX_EVIDENCE_ROWS, Math.max(1, MAX_EVIDENCE_CELLS / evidenceColumnCount));
    }

    private String renderRow(List<SuperAgentDocumentTableColumn> columns, RowView row) {
        return columns.stream()
            .map(column -> column.getColumnName() + "=" + StrUtil.blankToDefault(row.value(column.getColumnNo()), ""))
            .collect(Collectors.joining("；"));
    }

    private List<SuperAgentDocumentTableColumn> resolveEvidenceColumns(DocumentTableQuery query,
                                                                       List<SuperAgentDocumentTableColumn> columns,
                                                                       Map<String, SuperAgentDocumentTableColumn> columnByName) {
        Map<Integer, SuperAgentDocumentTableColumn> evidenceColumnMap = new LinkedHashMap<>();
        if (query.getOperation() == DocumentTableQuery.Operation.LOOKUP && query.getSelectedColumns() != null) {
            for (String selectedColumn : query.getSelectedColumns()) {
                SuperAgentDocumentTableColumn column = requiredColumn(columnByName, selectedColumn);
                evidenceColumnMap.put(column.getColumnNo(), column);
            }
        }
        if (query.getFilters() != null) {
            for (DocumentTableQuery.Filter filter : query.getFilters()) {
                addEvidenceColumn(evidenceColumnMap, columnByName, filter.getColumn());
            }
        }
        addEvidenceColumn(evidenceColumnMap, columnByName, query.getGroupByColumn());
        addEvidenceColumn(evidenceColumnMap, columnByName, query.getMetricColumn());
        if (evidenceColumnMap.isEmpty()) {
            columns.stream()
                .sorted(Comparator.comparing(SuperAgentDocumentTableColumn::getColumnNo))
                .limit(MAX_EVIDENCE_COLUMNS)
                .forEach(column -> evidenceColumnMap.put(column.getColumnNo(), column));
        }
        return evidenceColumnMap.values().stream()
            .sorted(Comparator.comparing(SuperAgentDocumentTableColumn::getColumnNo))
            .limit(MAX_EVIDENCE_COLUMNS)
            .toList();
    }

    private void addEvidenceColumn(Map<Integer, SuperAgentDocumentTableColumn> evidenceColumnMap,
                                   Map<String, SuperAgentDocumentTableColumn> columnByName,
                                   String columnName) {
        if (StrUtil.isBlank(columnName)) {
            return;
        }
        SuperAgentDocumentTableColumn column = columnByName.get(normalizeName(columnName));
        if (column != null) {
            evidenceColumnMap.put(column.getColumnNo(), column);
        }
    }

    private TableEvidenceLocation buildEvidenceLocation(List<RowView> rows, List<SuperAgentDocumentTableColumn> columns) {
        List<Long> rowIds = rows.stream().map(RowView::rowId).filter(Objects::nonNull).toList();
        List<Integer> rowNos = rows.stream().map(RowView::rowNo).filter(Objects::nonNull).toList();
        List<Long> columnIds = columns.stream().map(SuperAgentDocumentTableColumn::getId).filter(Objects::nonNull).toList();
        List<Integer> columnNos = columns.stream().map(SuperAgentDocumentTableColumn::getColumnNo).filter(Objects::nonNull).toList();
        List<String> columnNames = columns.stream().map(SuperAgentDocumentTableColumn::getColumnName).filter(StrUtil::isNotBlank).toList();
        List<Long> cellIds = new ArrayList<>();
        List<String> cellCoordinates = new ArrayList<>();
        List<String> cellBboxJsons = new ArrayList<>();
        for (RowView row : rows) {
            for (SuperAgentDocumentTableColumn column : columns) {
                CellView cell = row.values().get(column.getColumnNo());
                if (cell == null || cell.cellId() == null) {
                    continue;
                }
                if (cellIds.size() >= MAX_EVIDENCE_CELLS) {
                    return new TableEvidenceLocation(rowIds, rowNos, columnIds, columnNos, columnNames, cellIds, cellCoordinates, cellBboxJsons);
                }
                cellIds.add(cell.cellId());
                cellCoordinates.add(StrUtil.blankToDefault(cell.sourceCellRef(), "R" + row.rowNo() + "C" + column.getColumnNo()));
                cellBboxJsons.add(StrUtil.blankToDefault(cell.bboxJson(), ""));
            }
        }
        return new TableEvidenceLocation(rowIds, rowNos, columnIds, columnNos, columnNames, cellIds, cellCoordinates, cellBboxJsons);
    }

    private void deleteByTableIds(List<Long> tableIds) {
        if (CollUtil.isEmpty(tableIds)) {
            return;
        }
        cellMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTableCell>().in(SuperAgentDocumentTableCell::getTableId, tableIds));
        rowMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTableRow>().in(SuperAgentDocumentTableRow::getTableId, tableIds));
        columnMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTableColumn>().in(SuperAgentDocumentTableColumn::getTableId, tableIds));
    }

    private SuperAgentDocumentTable requiredTable(Long tableId) {
        SuperAgentDocumentTable table = tableMapper.selectById(tableId);
        if (table == null || !Objects.equals(table.getStatus(), BusinessStatus.YES.getCode())) {
            throw new IllegalArgumentException("未知表格: " + tableId);
        }
        return table;
    }

    private DocumentTableDescriptor toDescriptor(SuperAgentDocumentTable table,
                                                 Long indexTaskId,
                                                 List<SuperAgentDocumentTableColumn> columns) {
        return DocumentTableDescriptor.builder()
            .tableId(table.getId())
            .documentId(table.getDocumentId())
            .indexTaskId(indexTaskId)
            .taskId(table.getTaskId())
            .blockId(table.getBlockId())
            .tableNo(table.getTableNo())
            .title(table.getTitle())
            .sectionPath(table.getSectionPath())
            .pageNo(table.getPageNo())
            .pageRange(table.getPageRange())
            .bboxJson(table.getBboxJson())
            .rowCount(table.getRowCount())
            .columnCount(table.getColumnCount())
            .columns(columns.stream()
                .map(column -> DocumentTableDescriptor.Column.builder()
                    .columnNo(column.getColumnNo())
                    .columnName(column.getColumnName())
                    .normalizedName(column.getNormalizedName())
                    .valueType(column.getValueType())
                    .build())
                .toList())
            .build();
    }

    private record PendingTableRetrievalScope(Long documentId, Long indexTaskId, Long sourceParseTaskId) {
    }

    private record TableRetrievalScope(Long documentId, Long indexTaskId, Long sourceParseTaskId) {
    }

    private record TableArtifactScope(Long documentId, Long sourceParseTaskId) {
    }

    private record RowView(Long rowId, Integer rowNo, Map<Integer, CellView> values) {
        private String value(Integer columnNo) {
            CellView cell = values.get(columnNo);
            return cell == null ? "" : cell.text();
        }

        private Optional<BigDecimal> numeric(Integer columnNo) {
            CellView cell = values.get(columnNo);
            return cell == null || cell.numericValue() == null ? Optional.empty() : Optional.of(cell.numericValue());
        }
    }

    private record CellView(Long cellId,
                            Long columnId,
                            Integer columnNo,
                            String columnName,
                            String text,
                            BigDecimal numericValue,
                            String sourceCellRef,
                            String bboxJson) {
    }

    private record TableEvidenceLocation(List<Long> rowIds,
                                         List<Integer> rowNos,
                                         List<Long> columnIds,
                                         List<Integer> columnNos,
                                         List<String> columnNames,
                                         List<Long> cellIds,
                                         List<String> cellCoordinates,
                                         List<String> cellBboxJsons) {
    }

}
