package org.smartledge.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
@AllArgsConstructor
public class DocumentMarkdownSyntaxContract {

    public static final String SCHEMA_VERSION = "markdown-syntax.v1";

    private static final Set<String> SOURCE_ORIGINS = Set.of("SOURCE_MARKDOWN", "PROVIDER_MARKDOWN");
    private static final Set<String> NODE_TYPES = Set.of(
        "DOCUMENT",
        "HEADING",
        "PARAGRAPH",
        "ORDERED_LIST",
        "UNORDERED_LIST",
        "LIST_ITEM",
        "BLOCKQUOTE",
        "CODE_BLOCK",
        "THEMATIC_BREAK",
        "TABLE",
        "TABLE_HEAD",
        "TABLE_BODY",
        "TABLE_ROW",
        "TABLE_CELL",
        "HTML_BLOCK"
    );
    private static final Set<String> ALIGNMENTS = Set.of("LEFT", "CENTER", "RIGHT");

    private final ObjectMapper objectMapper;

    public DocumentMarkdownSyntaxCandidate read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Markdown 语法契约 artifact 为空");
        }
        try {
            DocumentMarkdownSyntaxCandidate syntax = strictMapper().readValue(bytes, DocumentMarkdownSyntaxCandidate.class);
            return validate(syntax);
        }
        catch (IOException exception) {
            throw new IllegalStateException("反序列化 Markdown 语法契约失败", exception);
        }
    }

    public byte[] write(DocumentMarkdownSyntaxCandidate syntax) {
        validate(syntax);
        try {
            return strictMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .writeValueAsBytes(syntax);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 Markdown 语法契约失败", exception);
        }
    }

    public DocumentMarkdownSyntaxCandidate validate(DocumentMarkdownSyntaxCandidate syntax) {
        if (syntax == null) {
            throw new IllegalStateException("Markdown 语法契约为空");
        }
        if (!SCHEMA_VERSION.equals(syntax.getSchemaVersion())) {
            throw new IllegalStateException("不支持的 Markdown 语法契约版本: " + syntax.getSchemaVersion());
        }
        if (!SOURCE_ORIGINS.contains(syntax.getSourceOrigin())) {
            throw new IllegalStateException("Markdown 语法契约 sourceOrigin 非法: " + syntax.getSourceOrigin());
        }
        if (syntax.getSourceText() == null) {
            throw new IllegalStateException("Markdown 语法契约缺少 sourceText");
        }
        byte[] sourceBytes = syntax.getSourceText().getBytes(UTF_8);
        if (syntax.getSourceLengthBytes() == null || syntax.getSourceLengthBytes() != sourceBytes.length) {
            throw new IllegalStateException("Markdown 语法契约 sourceLengthBytes 不守恒");
        }
        if (!DigestUtil.sha256Hex(sourceBytes).equals(syntax.getSourceSha256())) {
            throw new IllegalStateException("Markdown 语法契约 sourceSha256 不守恒");
        }
        if (syntax.getNodes() == null || syntax.getNodes().isEmpty()) {
            throw new IllegalStateException("Markdown 语法契约缺少 nodes");
        }

        Map<String, DocumentMarkdownSyntaxNodeCandidate> nodesById = new LinkedHashMap<>();
        for (int index = 0; index < syntax.getNodes().size(); index++) {
            DocumentMarkdownSyntaxNodeCandidate node = syntax.getNodes().get(index);
            validateNode(syntax, node, index, sourceBytes, nodesById);
            nodesById.put(node.getNodeId(), node);
        }
        validateTableStructure(nodesById);
        return syntax;
    }

    private void validateNode(DocumentMarkdownSyntaxCandidate syntax,
                              DocumentMarkdownSyntaxNodeCandidate node,
                              int index,
                              byte[] sourceBytes,
                              Map<String, DocumentMarkdownSyntaxNodeCandidate> nodesById) {
        if (node == null || node.getOrder() == null || node.getOrder() != index
            || StrUtil.isBlank(node.getNodeId()) || !NODE_TYPES.contains(node.getNodeType())
            || !syntax.getSourceOrigin().equals(node.getOrigin()) || node.getSourceSpan() == null
            || node.getText() == null) {
            throw new IllegalStateException("Markdown 语法契约 node 基础字段非法，order=" + index);
        }
        if (nodesById.containsKey(node.getNodeId())) {
            throw new IllegalStateException("Markdown 语法契约 nodeId 重复: " + node.getNodeId());
        }
        validateSpan(node, sourceBytes);
        validateTypeFields(node);

        if (index == 0) {
            if (!"DOCUMENT".equals(node.getNodeType()) || node.getParentNodeId() != null
                || node.getSourceSpan().getStartByte() != 0
                || node.getSourceSpan().getEndByte() != sourceBytes.length) {
                throw new IllegalStateException("Markdown 语法契约首节点不是完整 DOCUMENT root");
            }
            return;
        }
        if ("DOCUMENT".equals(node.getNodeType())) {
            throw new IllegalStateException("Markdown 语法契约只允许一个 DOCUMENT root");
        }

        DocumentMarkdownSyntaxNodeCandidate parent = nodesById.get(node.getParentNodeId());
        if (parent == null) {
            throw new IllegalStateException("Markdown 语法契约 parent 必须先于 child: " + node.getNodeId());
        }
        DocumentMarkdownSourceSpanCandidate span = node.getSourceSpan();
        DocumentMarkdownSourceSpanCandidate parentSpan = parent.getSourceSpan();
        if (span.getStartByte() < parentSpan.getStartByte() || span.getEndByte() > parentSpan.getEndByte()) {
            throw new IllegalStateException("Markdown 语法契约 child span 越过 parent: " + node.getNodeId());
        }
        if ("LIST_ITEM".equals(node.getNodeType())) {
            if ("ORDERED_LIST".equals(parent.getNodeType()) && node.getOrdinal() == null) {
                throw new IllegalStateException("ordered LIST_ITEM 缺少 ordinal: " + node.getNodeId());
            }
            if ("UNORDERED_LIST".equals(parent.getNodeType()) && node.getOrdinal() != null) {
                throw new IllegalStateException("unordered LIST_ITEM 不得定义 ordinal: " + node.getNodeId());
            }
            if (!"ORDERED_LIST".equals(parent.getNodeType()) && !"UNORDERED_LIST".equals(parent.getNodeType())) {
                throw new IllegalStateException("LIST_ITEM parent 必须是 list container: " + node.getNodeId());
            }
        }
    }

    private void validateSpan(DocumentMarkdownSyntaxNodeCandidate node, byte[] sourceBytes) {
        DocumentMarkdownSourceSpanCandidate span = node.getSourceSpan();
        if (span.getStartByte() == null || span.getEndByte() == null
            || span.getStartByte() < 0 || span.getEndByte() < span.getStartByte()
            || span.getEndByte() > sourceBytes.length
            || span.getStartLine() == null || span.getStartColumn() == null
            || span.getEndLine() == null || span.getEndColumn() == null
            || span.getStartLine() < 1 || span.getStartColumn() < 1
            || span.getEndLine() < 1 || span.getEndColumn() < 1
            || span.getEndLine() < span.getStartLine()
            || (span.getEndLine().equals(span.getStartLine()) && span.getEndColumn() < span.getStartColumn())) {
            throw new IllegalStateException("Markdown 语法契约 sourceSpan 非法: " + node.getNodeId());
        }
        byte[] slice = Arrays.copyOfRange(sourceBytes, span.getStartByte(), span.getEndByte());
        try {
            UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(slice));
        }
        catch (CharacterCodingException exception) {
            throw new IllegalStateException("Markdown 语法契约 sourceSpan 切断 UTF-8 字符: " + node.getNodeId(), exception);
        }
    }

    private void validateTypeFields(DocumentMarkdownSyntaxNodeCandidate node) {
        if ("HEADING".equals(node.getNodeType())) {
            if (node.getLevel() == null || node.getLevel() < 1 || node.getLevel() > 6 || StrUtil.isBlank(node.getMarker())) {
                throw new IllegalStateException("HEADING 缺少合法 level/marker: " + node.getNodeId());
            }
        }
        else if (node.getLevel() != null) {
            throw new IllegalStateException(node.getNodeType() + " 不得定义 heading level: " + node.getNodeId());
        }
        if ("LIST_ITEM".equals(node.getNodeType()) && StrUtil.isBlank(node.getMarker())) {
            throw new IllegalStateException("LIST_ITEM 缺少原始 marker: " + node.getNodeId());
        }
        if ("TABLE_CELL".equals(node.getNodeType())) {
            if (node.getHeader() == null || node.getRowIndex() == null || node.getRowIndex() < 0
                || node.getColumnIndex() == null || node.getColumnIndex() < 0
                || (node.getAlignment() != null && !ALIGNMENTS.contains(node.getAlignment()))) {
                throw new IllegalStateException("TABLE_CELL 缺少合法字段: " + node.getNodeId());
            }
        }
        else if (node.getHeader() != null || node.getAlignment() != null
            || node.getRowIndex() != null || node.getColumnIndex() != null) {
            throw new IllegalStateException(node.getNodeType() + " 不得定义 table-cell 字段: " + node.getNodeId());
        }
    }

    private void validateTableStructure(Map<String, DocumentMarkdownSyntaxNodeCandidate> nodesById) {
        Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> childrenByParent = new LinkedHashMap<>();
        for (DocumentMarkdownSyntaxNodeCandidate node : nodesById.values()) {
            if (node.getParentNodeId() != null) {
                childrenByParent.computeIfAbsent(node.getParentNodeId(), ignored -> new java.util.ArrayList<>()).add(node);
            }
            DocumentMarkdownSyntaxNodeCandidate parent = nodesById.get(node.getParentNodeId());
            if ("TABLE_HEAD".equals(node.getNodeType()) || "TABLE_BODY".equals(node.getNodeType())) {
                if (parent == null || !"TABLE".equals(parent.getNodeType())) {
                    throw new IllegalStateException(node.getNodeType() + " parent 必须是 TABLE: " + node.getNodeId());
                }
            }
            else if ("TABLE_ROW".equals(node.getNodeType())) {
                if (parent == null || (!"TABLE_HEAD".equals(parent.getNodeType()) && !"TABLE_BODY".equals(parent.getNodeType()))) {
                    throw new IllegalStateException("TABLE_ROW parent 必须是 TABLE_HEAD/TABLE_BODY: " + node.getNodeId());
                }
            }
            else if ("TABLE_CELL".equals(node.getNodeType())) {
                if (parent == null || !"TABLE_ROW".equals(parent.getNodeType())) {
                    throw new IllegalStateException("TABLE_CELL parent 必须是 TABLE_ROW: " + node.getNodeId());
                }
            }
        }

        for (DocumentMarkdownSyntaxNodeCandidate table : nodesById.values()) {
            if (!"TABLE".equals(table.getNodeType())) {
                continue;
            }
            List<DocumentMarkdownSyntaxNodeCandidate> tableChildren = childrenByParent.getOrDefault(table.getNodeId(), List.of());
            List<DocumentMarkdownSyntaxNodeCandidate> heads = tableChildren.stream()
                .filter(node -> "TABLE_HEAD".equals(node.getNodeType()))
                .toList();
            List<DocumentMarkdownSyntaxNodeCandidate> bodies = tableChildren.stream()
                .filter(node -> "TABLE_BODY".equals(node.getNodeType()))
                .toList();
            if (heads.size() != 1 || bodies.size() != 1 || tableChildren.size() != 2) {
                throw new IllegalStateException("TABLE 必须且只能包含一个 TABLE_HEAD 和一个 TABLE_BODY: " + table.getNodeId());
            }
            List<DocumentMarkdownSyntaxNodeCandidate> headerRows = childrenByParent.getOrDefault(heads.get(0).getNodeId(), List.of());
            if (headerRows.size() != 1 || !"TABLE_ROW".equals(headerRows.get(0).getNodeType())) {
                throw new IllegalStateException("TABLE_HEAD 必须且只能包含一个 TABLE_ROW: " + table.getNodeId());
            }
            List<DocumentMarkdownSyntaxNodeCandidate> bodyRows = childrenByParent.getOrDefault(bodies.get(0).getNodeId(), List.of());
            if (bodyRows.stream().anyMatch(node -> !"TABLE_ROW".equals(node.getNodeType()))) {
                throw new IllegalStateException("TABLE_BODY 只能包含 TABLE_ROW: " + table.getNodeId());
            }
            List<DocumentMarkdownSyntaxNodeCandidate> rows = new java.util.ArrayList<>();
            rows.add(headerRows.get(0));
            rows.addAll(bodyRows);
            validateTableRows(table, rows, childrenByParent);
        }
    }

    private void validateTableRows(DocumentMarkdownSyntaxNodeCandidate table,
                                   List<DocumentMarkdownSyntaxNodeCandidate> rows,
                                   Map<String, List<DocumentMarkdownSyntaxNodeCandidate>> childrenByParent) {
        int columnCount = -1;
        List<String> headerAlignments = List.of();
        for (int expectedRowIndex = 0; expectedRowIndex < rows.size(); expectedRowIndex++) {
            DocumentMarkdownSyntaxNodeCandidate row = rows.get(expectedRowIndex);
            List<DocumentMarkdownSyntaxNodeCandidate> cells = childrenByParent.getOrDefault(row.getNodeId(), List.of());
            if (cells.isEmpty() || cells.stream().anyMatch(node -> !"TABLE_CELL".equals(node.getNodeType()))) {
                throw new IllegalStateException("TABLE_ROW 必须包含非空 TABLE_CELL: " + row.getNodeId());
            }
            boolean expectedHeader = expectedRowIndex == 0;
            for (int expectedColumnIndex = 0; expectedColumnIndex < cells.size(); expectedColumnIndex++) {
                DocumentMarkdownSyntaxNodeCandidate cell = cells.get(expectedColumnIndex);
                if (!Objects.equals(cell.getRowIndex(), expectedRowIndex)
                    || !Objects.equals(cell.getColumnIndex(), expectedColumnIndex)) {
                    throw new IllegalStateException("TABLE_CELL rowIndex/columnIndex 不连续: " + cell.getNodeId());
                }
                if (!Objects.equals(cell.getHeader(), expectedHeader)) {
                    throw new IllegalStateException((expectedHeader ? "TABLE_HEAD" : "TABLE_BODY")
                        + " cell header 标记不守恒: " + cell.getNodeId());
                }
            }
            if (columnCount < 0) {
                columnCount = cells.size();
                headerAlignments = cells.stream().map(DocumentMarkdownSyntaxNodeCandidate::getAlignment).toList();
            }
            else if (cells.size() != columnCount) {
                throw new IllegalStateException("TABLE 每行 columnCount 不守恒: " + table.getNodeId());
            }
            for (int columnIndex = 0; columnIndex < cells.size(); columnIndex++) {
                if (!Objects.equals(cells.get(columnIndex).getAlignment(), headerAlignments.get(columnIndex))) {
                    throw new IllegalStateException("TABLE column alignment 不守恒: " + cells.get(columnIndex).getNodeId());
                }
            }
        }
    }

    private ObjectMapper strictMapper() {
        return objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
