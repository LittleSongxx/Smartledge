package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionRequest;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagTableProjectionPort;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.manage.data.SuperAgentDocumentTable;
import org.smartledge.ai.manage.data.SuperAgentDocumentTableCell;
import org.smartledge.ai.manage.data.SuperAgentDocumentTableColumn;
import org.smartledge.ai.manage.data.SuperAgentDocumentTableRow;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableCellMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableColumnMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTableRowMapper;
import org.smartledge.enums.BusinessStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Reads only complete, bounded table projections; GraphRAG never reparses table markup. */
@Component
public class PersistedGraphRagTableProjectionAdapter implements GraphRagTableProjectionPort {

    private static final int MAX_COLUMNS = 12;
    private static final int MAX_ROWS = 200;
    private static final int MAX_CELL_CHARS = 500;
    private static final Pattern BLOCK_ID = Pattern.compile("[0-9]+");

    private final SuperAgentDocumentTableMapper tableMapper;
    private final SuperAgentDocumentTableColumnMapper columnMapper;
    private final SuperAgentDocumentTableRowMapper rowMapper;
    private final SuperAgentDocumentTableCellMapper cellMapper;

    public PersistedGraphRagTableProjectionAdapter(SuperAgentDocumentTableMapper tableMapper,
            SuperAgentDocumentTableColumnMapper columnMapper, SuperAgentDocumentTableRowMapper rowMapper,
            SuperAgentDocumentTableCellMapper cellMapper) {
        this.tableMapper = tableMapper;
        this.columnMapper = columnMapper;
        this.rowMapper = rowMapper;
        this.cellMapper = cellMapper;
    }

    @Override
    public Map<Long, List<GraphRagExtractionRequest.StructuredTable>> load(Long documentId,
            Long sourceParseTaskId, List<SuperAgentDocumentChunk> chunks) {
        Map<Long, List<Long>> chunkIdsByBlock = chunkIdsByBlock(chunks);
        if (documentId == null || sourceParseTaskId == null || chunkIdsByBlock.isEmpty()) {
            return Map.of();
        }
        List<SuperAgentDocumentTable> tables = safe(tableMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentTable>()
                        .eq(SuperAgentDocumentTable::getDocumentId, documentId)
                        .eq(SuperAgentDocumentTable::getTaskId, sourceParseTaskId)
                        .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode())
                        .in(SuperAgentDocumentTable::getBlockId, chunkIdsByBlock.keySet())
                        .orderByAsc(SuperAgentDocumentTable::getTableNo, SuperAgentDocumentTable::getId)));
        tables = tables.stream().filter(table -> table != null && table.getId() != null).toList();
        List<Long> tableIds = tables.stream().map(SuperAgentDocumentTable::getId).toList();
        if (tableIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<SuperAgentDocumentTableColumn>> columns = safe(columnMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentTableColumn>()
                        .in(SuperAgentDocumentTableColumn::getTableId, tableIds)
                        .eq(SuperAgentDocumentTableColumn::getStatus, BusinessStatus.YES.getCode())
                        .orderByAsc(SuperAgentDocumentTableColumn::getColumnNo,
                                SuperAgentDocumentTableColumn::getId)))
                .stream().collect(Collectors.groupingBy(SuperAgentDocumentTableColumn::getTableId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<SuperAgentDocumentTableRow>> rows = safe(rowMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentTableRow>()
                        .in(SuperAgentDocumentTableRow::getTableId, tableIds)
                        .eq(SuperAgentDocumentTableRow::getStatus, BusinessStatus.YES.getCode())
                        .orderByAsc(SuperAgentDocumentTableRow::getRowNo, SuperAgentDocumentTableRow::getId)))
                .stream().collect(Collectors.groupingBy(SuperAgentDocumentTableRow::getTableId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<SuperAgentDocumentTableCell>> cells = safe(cellMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentTableCell>()
                        .in(SuperAgentDocumentTableCell::getTableId, tableIds)
                        .eq(SuperAgentDocumentTableCell::getStatus, BusinessStatus.YES.getCode())
                        .orderByAsc(SuperAgentDocumentTableCell::getRowNo,
                                SuperAgentDocumentTableCell::getColumnNo, SuperAgentDocumentTableCell::getId)))
                .stream().collect(Collectors.groupingBy(SuperAgentDocumentTableCell::getTableId,
                        LinkedHashMap::new, Collectors.toList()));

        Map<Long, List<GraphRagExtractionRequest.StructuredTable>> result = new LinkedHashMap<>();
        for (SuperAgentDocumentTable table : tables) {
            GraphRagExtractionRequest.StructuredTable structured = toStructured(table,
                    columns.getOrDefault(table.getId(), List.of()), rows.getOrDefault(table.getId(), List.of()),
                    cells.getOrDefault(table.getId(), List.of()));
            if (structured == null) {
                continue;
            }
            for (Long chunkId : chunkIdsByBlock.getOrDefault(table.getBlockId(), List.of())) {
                result.computeIfAbsent(chunkId, ignored -> new ArrayList<>()).add(structured);
            }
        }
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }

    private GraphRagExtractionRequest.StructuredTable toStructured(SuperAgentDocumentTable table,
            List<SuperAgentDocumentTableColumn> columns, List<SuperAgentDocumentTableRow> rows,
            List<SuperAgentDocumentTableCell> cells) {
        if (table == null || table.getId() == null || table.getBlockId() == null || columns.size() < 2
                || columns.size() > MAX_COLUMNS || rows.isEmpty() || rows.size() > MAX_ROWS) {
            return null;
        }
        List<SuperAgentDocumentTableColumn> orderedColumns = columns.stream()
                .filter(column -> column != null && column.getColumnNo() != null
                        && StrUtil.isNotBlank(column.getColumnName()))
                .sorted(Comparator.comparing(SuperAgentDocumentTableColumn::getColumnNo)).toList();
        Set<Integer> columnNos = orderedColumns.stream().map(SuperAgentDocumentTableColumn::getColumnNo)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (orderedColumns.size() != columns.size() || columnNos.size() != columns.size()) {
            return null;
        }
        List<SuperAgentDocumentTableRow> orderedRows = rows.stream()
                .filter(row -> row != null && row.getRowNo() != null)
                .sorted(Comparator.comparing(SuperAgentDocumentTableRow::getRowNo)).toList();
        Set<Integer> rowNos = orderedRows.stream().map(SuperAgentDocumentTableRow::getRowNo)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (orderedRows.size() != rows.size() || rowNos.size() != rows.size()) {
            return null;
        }
        List<SuperAgentDocumentTableCell> usableCells = cells.stream()
                .filter(cell -> cell != null && cell.getRowNo() != null && cell.getColumnNo() != null
                        && rowNos.contains(cell.getRowNo()) && columnNos.contains(cell.getColumnNo())
                        && acceptableCell(cell.getCellText()))
                .toList();
        if (usableCells.size() != cells.size()) {
            return null;
        }
        Map<Integer, List<SuperAgentDocumentTableCell>> cellsByRow = usableCells.stream()
                .collect(Collectors.groupingBy(SuperAgentDocumentTableCell::getRowNo,
                        LinkedHashMap::new, Collectors.toList()));
        List<GraphRagExtractionRequest.StructuredTableRow> structuredRows = new ArrayList<>();
        for (SuperAgentDocumentTableRow row : orderedRows) {
            List<SuperAgentDocumentTableCell> rowCells = cellsByRow.getOrDefault(row.getRowNo(), List.of()).stream()
                    .sorted(Comparator.comparing(SuperAgentDocumentTableCell::getColumnNo)).toList();
            Set<Integer> rowColumnNos = rowCells.stream().map(SuperAgentDocumentTableCell::getColumnNo)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (rowColumnNos.size() != columnNos.size() || rowColumnNos.size() != rowCells.size()
                    || !rowColumnNos.equals(columnNos)) {
                continue;
            }
            structuredRows.add(new GraphRagExtractionRequest.StructuredTableRow(row.getRowNo(),
                    StrUtil.blankToDefault(row.getRowText(), ""), rowCells.stream()
                            .map(cell -> new GraphRagExtractionRequest.StructuredTableCell(cell.getColumnNo(),
                                    cell.getCellText().trim()))
                            .toList()));
        }
        if (structuredRows.isEmpty()) {
            return null;
        }
        return new GraphRagExtractionRequest.StructuredTable(table.getId(), table.getBlockId(), table.getTableNo(),
                StrUtil.blankToDefault(table.getTitle(), ""), orderedColumns.stream()
                        .map(column -> new GraphRagExtractionRequest.StructuredTableColumn(column.getColumnNo(),
                                column.getColumnName().trim()))
                        .toList(), List.copyOf(structuredRows));
    }

    private boolean acceptableCell(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.codePointCount(0, trimmed.length()) <= MAX_CELL_CHARS;
    }

    private Map<Long, List<Long>> chunkIdsByBlock(List<SuperAgentDocumentChunk> chunks) {
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (SuperAgentDocumentChunk chunk : safe(chunks)) {
            if (chunk == null || chunk.getId() == null) {
                continue;
            }
            Matcher matcher = BLOCK_ID.matcher(StrUtil.blankToDefault(chunk.getSourceBlockIds(), ""));
            Set<Long> blockIds = new LinkedHashSet<>();
            while (matcher.find()) {
                blockIds.add(Long.parseLong(matcher.group()));
            }
            for (Long blockId : blockIds) {
                result.computeIfAbsent(blockId, ignored -> new ArrayList<>()).add(chunk.getId());
            }
        }
        return result;
    }

    private static <T> List<T> safe(Collection<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
