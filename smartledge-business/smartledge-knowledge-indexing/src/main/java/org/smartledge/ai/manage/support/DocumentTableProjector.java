package org.smartledge.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class DocumentTableProjector {

    public static final String MARKDOWN_PROJECTION_OWNER = "JAVA_MARKDOWN_SYNTAX_V1";
    public static final String PROVIDER_PROJECTION_OWNER = "JAVA_PROVIDER_TABLE_ROWS";

    private final DocumentMarkdownSyntaxContract syntaxContract;
    private final ObjectMapper objectMapper;

    public List<DocumentTableCandidate> projectMarkdown(DocumentMarkdownSyntaxCandidate syntax,
                                                        List<DocumentBlockCandidate> blocks) {
        DocumentMarkdownSyntaxCandidate validated = syntaxContract.validate(syntax);
        Map<String, DocumentBlockCandidate> tableBlocksBySyntaxNodeId = markdownTableBlocks(blocks).stream()
            .collect(Collectors.toMap(
                this::requiredSyntaxNodeId,
                Function.identity(),
                (left, right) -> {
                    throw new IllegalStateException("Markdown TABLE block syntaxNodeId 重复");
                },
                LinkedHashMap::new
            ));
        Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> childrenByParent = childrenByParent(validated.getNodes());
        List<DocumentTableCandidate> result = new ArrayList<>();
        for (DocumentMarkdownSyntaxNodeCandidate tableNode : validated.getNodes()) {
            if (!"TABLE".equals(tableNode.getNodeType())) {
                continue;
            }
            DocumentBlockCandidate block = tableBlocksBySyntaxNodeId.get(tableNode.getNodeId());
            if (block == null) {
                throw new IllegalStateException("Markdown TABLE 缺少同源 block: " + tableNode.getNodeId());
            }
            result.add(projectMarkdownTable(validated, tableNode, block, childrenByParent));
        }
        if (result.size() != tableBlocksBySyntaxNodeId.size()) {
            throw new IllegalStateException("Markdown TABLE syntax 与 block 数量不守恒");
        }
        return List.copyOf(result);
    }

    public List<DocumentTableCandidate> projectProviderBlocks(List<DocumentBlockCandidate> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<DocumentTableCandidate> result = new ArrayList<>();
        for (DocumentBlockCandidate block : blocks) {
            if (block == null || !"TABLE".equalsIgnoreCase(StrUtil.blankToDefault(block.getBlockType(), ""))
                || block.getTableRows() == null || block.getTableRows().isEmpty()) {
                continue;
            }
            result.add(projectProviderTable(block));
        }
        return List.copyOf(result);
    }

    private DocumentTableCandidate projectMarkdownTable(
        DocumentMarkdownSyntaxCandidate syntax,
        DocumentMarkdownSyntaxNodeCandidate tableNode,
        DocumentBlockCandidate block,
        Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> childrenByParent) {
        List<DocumentMarkdownSyntaxNodeCandidate> rowNodes = descendants(tableNode.getNodeId(), childrenByParent).stream()
            .filter(node -> "TABLE_ROW".equals(node.getNodeType()))
            .toList();
        List<DocumentTableCandidate.Row> rows = rowNodes.stream()
            .map(row -> markdownRow(row, childrenByParent))
            .sorted(Comparator.comparing(DocumentTableCandidate.Row::rowIndex))
            .toList();
        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put("projectionOwner", MARKDOWN_PROJECTION_OWNER);
        sourceMetadata.put("syntaxSchemaVersion", syntax.getSchemaVersion());
        sourceMetadata.put("sourceOrigin", syntax.getSourceOrigin());
        sourceMetadata.put("sourceSha256", syntax.getSourceSha256());
        sourceMetadata.put("syntaxNodeId", tableNode.getNodeId());
        sourceMetadata.put("sourceSpan", spanMap(tableNode.getSourceSpan()));
        return new DocumentTableCandidate(
            block.getBlockNo(),
            StrUtil.blankToDefault(block.getSectionPath(), ""),
            block.getPageNo(),
            StrUtil.blankToDefault(block.getPageRange(), ""),
            StrUtil.blankToDefault(block.getBboxJson(), ""),
            StrUtil.blankToDefault(block.getTableHtml(), ""),
            "",
            MARKDOWN_PROJECTION_OWNER,
            syntax.getSchemaVersion(),
            syntax.getSourceOrigin(),
            syntax.getSourceSha256(),
            tableNode.getNodeId(),
            DocumentTableCandidate.SourceSpan.from(tableNode.getSourceSpan()),
            rows,
            sourceMetadata
        );
    }

    private DocumentTableCandidate.Row markdownRow(
        DocumentMarkdownSyntaxNodeCandidate rowNode,
        Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> childrenByParent) {
        List<DocumentMarkdownSyntaxNodeCandidate> cellNodes = childrenByParent.getOrDefault(rowNode.getNodeId(), List.of()).stream()
            .filter(node -> "TABLE_CELL".equals(node.getNodeType()))
            .sorted(Comparator.comparing(DocumentMarkdownSyntaxNodeCandidate::getColumnIndex))
            .toList();
        if (cellNodes.isEmpty()) {
            throw new IllegalStateException("TABLE_ROW 缺少 TABLE_CELL: " + rowNode.getNodeId());
        }
        int rowIndex = cellNodes.get(0).getRowIndex();
        boolean header = Boolean.TRUE.equals(cellNodes.get(0).getHeader());
        List<DocumentTableCandidate.Cell> cells = cellNodes.stream()
            .map(cell -> markdownCell(rowNode, cell))
            .toList();
        return new DocumentTableCandidate.Row(
            rowIndex,
            header,
            rowIndex + 1,
            rowNode.getNodeId(),
            DocumentTableCandidate.SourceSpan.from(rowNode.getSourceSpan()),
            cells
        );
    }

    private DocumentTableCandidate.Cell markdownCell(DocumentMarkdownSyntaxNodeCandidate rowNode,
                                                      DocumentMarkdownSyntaxNodeCandidate cellNode) {
        DocumentTableCandidate.SourceSpan span = DocumentTableCandidate.SourceSpan.from(cellNode.getSourceSpan());
        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put("projectionOwner", MARKDOWN_PROJECTION_OWNER);
        sourceMetadata.put("rowSyntaxNodeId", rowNode.getNodeId());
        sourceMetadata.put("rowSourceSpan", spanMap(rowNode.getSourceSpan()));
        sourceMetadata.put("syntaxNodeId", cellNode.getNodeId());
        sourceMetadata.put("sourceSpan", spanMap(cellNode.getSourceSpan()));
        sourceMetadata.put("header", cellNode.getHeader());
        if (StrUtil.isNotBlank(cellNode.getAlignment())) {
            sourceMetadata.put("alignment", cellNode.getAlignment());
        }
        return new DocumentTableCandidate.Cell(
            cellNode.getRowIndex(),
            cellNode.getColumnIndex(),
            Boolean.TRUE.equals(cellNode.getHeader()),
            StrUtil.blankToDefault(cellNode.getAlignment(), ""),
            StrUtil.blankToDefault(cellNode.getText(), ""),
            cellNode.getRowIndex() + 1,
            cellNode.getColumnIndex() + 1,
            sourceRef(span),
            "",
            cellNode.getNodeId(),
            span,
            sourceMetadata
        );
    }

    private DocumentTableCandidate projectProviderTable(DocumentBlockCandidate block) {
        List<List<String>> normalizedRows = normalizeProviderRows(block.getTableRows());
        int columnCount = normalizedRows.stream().mapToInt(List::size).max().orElse(0);
        if (columnCount == 0) {
            throw new IllegalStateException("provider TABLE 缺少列: blockNo=" + block.getBlockNo());
        }
        JsonNode metadataRoot = readMetadata(block.getMetadataJson());
        Map<CellPosition, JsonNode> metadataByPosition = providerCellMetadata(metadataRoot.path("tableCellMetadata"));
        List<DocumentTableCandidate.Row> rows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < normalizedRows.size(); rowIndex++) {
            List<String> values = padRow(normalizedRows.get(rowIndex), columnCount);
            List<DocumentTableCandidate.Cell> cells = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                int sourceRowNo = rowIndex + 1;
                int sourceColumnNo = columnIndex + 1;
                JsonNode metadata = metadataByPosition.get(new CellPosition(sourceRowNo, sourceColumnNo));
                Map<String, Object> sourceMetadata = metadata == null
                    ? Map.of()
                    : objectMapper.convertValue(metadata, new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
                Integer mappedSourceRowNo = readInteger(metadata, "sourceRowNo", sourceRowNo);
                Integer mappedSourceColumnNo = readInteger(metadata, "sourceColumnNo", sourceColumnNo);
                String sourceCellRef = firstNonBlank(
                    readText(metadata, "excelAddress"),
                    readText(metadata, "cellCoordinate"),
                    "R" + mappedSourceRowNo + "C" + mappedSourceColumnNo
                );
                cells.add(new DocumentTableCandidate.Cell(
                    rowIndex,
                    columnIndex,
                    rowIndex == 0,
                    "",
                    values.get(columnIndex),
                    mappedSourceRowNo,
                    mappedSourceColumnNo,
                    sourceCellRef,
                    readText(metadata, "bboxJson"),
                    "",
                    null,
                    sourceMetadata
                ));
            }
            rows.add(new DocumentTableCandidate.Row(rowIndex, rowIndex == 0, rowIndex + 1, "", null, cells));
        }
        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put("projectionOwner", PROVIDER_PROJECTION_OWNER);
        return new DocumentTableCandidate(
            block.getBlockNo(),
            StrUtil.blankToDefault(block.getSectionPath(), ""),
            block.getPageNo(),
            StrUtil.blankToDefault(block.getPageRange(), ""),
            StrUtil.blankToDefault(block.getBboxJson(), ""),
            StrUtil.blankToDefault(block.getTableHtml(), ""),
            providerTitle(metadataRoot),
            PROVIDER_PROJECTION_OWNER,
            "",
            "",
            "",
            "",
            null,
            rows,
            sourceMetadata
        );
    }

    private List<DocumentBlockCandidate> markdownTableBlocks(List<DocumentBlockCandidate> blocks) {
        return (blocks == null ? List.<DocumentBlockCandidate>of() : blocks).stream()
            .filter(Objects::nonNull)
            .filter(block -> "TABLE".equalsIgnoreCase(StrUtil.blankToDefault(block.getBlockType(), "")))
            .toList();
    }

    private String requiredSyntaxNodeId(DocumentBlockCandidate block) {
        String syntaxNodeId = readMetadata(block.getMetadataJson()).path("syntaxNodeId").asText("");
        if (StrUtil.isBlank(syntaxNodeId)) {
            throw new IllegalStateException("Markdown TABLE block 缺少 syntaxNodeId: blockNo=" + block.getBlockNo());
        }
        return syntaxNodeId;
    }

    private Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> childrenByParent(
        List<DocumentMarkdownSyntaxNodeCandidate> nodes) {
        Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> result = new LinkedHashMap<>();
        for (DocumentMarkdownSyntaxNodeCandidate node : nodes) {
            if (node.getParentNodeId() != null) {
                result.computeIfAbsent(node.getParentNodeId(), ignored -> new ArrayList<>()).add(node);
            }
        }
        return result;
    }

    private List<DocumentMarkdownSyntaxNodeCandidate> descendants(
        String nodeId,
        Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> childrenByParent) {
        List<DocumentMarkdownSyntaxNodeCandidate> result = new ArrayList<>();
        List<DocumentMarkdownSyntaxNodeCandidate> pending = new ArrayList<>(childrenByParent.getOrDefault(nodeId, List.of()));
        while (!pending.isEmpty()) {
            DocumentMarkdownSyntaxNodeCandidate current = pending.remove(0);
            result.add(current);
            pending.addAll(0, childrenByParent.getOrDefault(current.getNodeId(), List.of()));
        }
        return result;
    }

    private JsonNode readMetadata(String metadataJson) {
        if (StrUtil.isBlank(metadataJson)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("table block metadata 必须是 JSON object");
            }
            return root;
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("table block metadata JSON 非法", exception);
        }
    }

    private Map<CellPosition, JsonNode> providerCellMetadata(JsonNode metadataNode) {
        Map<CellPosition, JsonNode> result = new LinkedHashMap<>();
        if (metadataNode == null || !metadataNode.isArray()) {
            return result;
        }
        for (JsonNode item : metadataNode) {
            Integer rowNo = readInteger(item, "rowNo", null);
            Integer columnNo = readInteger(item, "columnNo", null);
            if (rowNo != null && rowNo > 0 && columnNo != null && columnNo > 0) {
                result.put(new CellPosition(rowNo, columnNo), item);
            }
        }
        return result;
    }

    private String providerTitle(JsonNode metadataRoot) {
        JsonNode titleRows = metadataRoot.path("tableTitleRows");
        if (!titleRows.isArray()) {
            return "";
        }
        List<String> titles = new ArrayList<>();
        for (JsonNode title : titleRows) {
            if (StrUtil.isNotBlank(title.asText(""))) {
                titles.add(title.asText().trim());
            }
        }
        return String.join(" / ", titles);
    }

    private List<List<String>> normalizeProviderRows(List<List<String>> rows) {
        List<List<String>> normalized = new ArrayList<>();
        for (List<String> row : rows == null ? List.<List<String>>of() : rows) {
            if (row == null) {
                throw new IllegalStateException("provider TABLE 包含 null row");
            }
            normalized.add(row.stream().map(value -> StrUtil.blankToDefault(value, "").trim()).toList());
        }
        return normalized;
    }

    private List<String> padRow(List<String> row, int columnCount) {
        List<String> padded = new ArrayList<>(row);
        while (padded.size() < columnCount) {
            padded.add("");
        }
        if (padded.size() > columnCount) {
            throw new IllegalStateException("provider TABLE row 超过已冻结 columnCount");
        }
        return List.copyOf(padded);
    }

    private Map<String, Object> spanMap(DocumentMarkdownSourceSpanCandidate span) {
        if (span == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startByte", span.getStartByte());
        result.put("endByte", span.getEndByte());
        result.put("startLine", span.getStartLine());
        result.put("startColumn", span.getStartColumn());
        result.put("endLine", span.getEndLine());
        result.put("endColumn", span.getEndColumn());
        return result;
    }

    private String sourceRef(DocumentTableCandidate.SourceSpan span) {
        if (span == null) {
            return "";
        }
        return "L" + span.startLine() + "C" + span.startColumn()
            + "-L" + span.endLine() + "C" + span.endColumn();
    }

    private Integer readInteger(JsonNode node, String fieldName, Integer defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.path(fieldName);
        if (value.isIntegralNumber()) {
            return value.intValue();
        }
        if (value.isTextual() && StrUtil.isNotBlank(value.asText())) {
            try {
                return Integer.parseInt(value.asText().trim());
            }
            catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String readText(JsonNode node, String fieldName) {
        return node == null || node.isMissingNode() || node.isNull()
            ? ""
            : node.path(fieldName).asText("");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private record CellPosition(Integer rowNo, Integer columnNo) {
    }
}
