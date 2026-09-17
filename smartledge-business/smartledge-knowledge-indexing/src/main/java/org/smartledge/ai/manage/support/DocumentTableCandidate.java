package org.smartledge.ai.manage.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DocumentTableCandidate(
    Integer sourceBlockNo,
    String sectionPath,
    Integer pageNo,
    String pageRange,
    String bboxJson,
    String tableHtml,
    String titleHint,
    String projectionOwner,
    String schemaVersion,
    String sourceOrigin,
    String sourceSha256,
    String syntaxNodeId,
    SourceSpan sourceSpan,
    List<Row> rows,
    Map<String, Object> sourceMetadata
) {

    public DocumentTableCandidate {
        rows = rows == null ? List.of() : List.copyOf(rows);
        sourceMetadata = sourceMetadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(sourceMetadata));
    }

    public record Row(
        Integer rowIndex,
        boolean header,
        Integer sourceRowNo,
        String syntaxNodeId,
        SourceSpan sourceSpan,
        List<Cell> cells
    ) {

        public Row {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    public record Cell(
        Integer rowIndex,
        Integer columnIndex,
        boolean header,
        String alignment,
        String text,
        Integer sourceRowNo,
        Integer sourceColumnNo,
        String sourceCellRef,
        String bboxJson,
        String syntaxNodeId,
        SourceSpan sourceSpan,
        Map<String, Object> sourceMetadata
    ) {

        public Cell {
            sourceMetadata = sourceMetadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceMetadata));
        }
    }

    public record SourceSpan(
        Integer startByte,
        Integer endByte,
        Integer startLine,
        Integer startColumn,
        Integer endLine,
        Integer endColumn
    ) {

        public static SourceSpan from(DocumentMarkdownSourceSpanCandidate source) {
            if (source == null) {
                return null;
            }
            return new SourceSpan(
                source.getStartByte(),
                source.getEndByte(),
                source.getStartLine(),
                source.getStartColumn(),
                source.getEndLine(),
                source.getEndColumn()
            );
        }
    }
}
