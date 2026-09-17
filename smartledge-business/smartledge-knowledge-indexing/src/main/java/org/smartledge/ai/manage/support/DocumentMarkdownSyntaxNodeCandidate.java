package org.smartledge.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMarkdownSyntaxNodeCandidate {

    private Integer order;

    private String nodeId;

    private String parentNodeId;

    private String nodeType;

    private String origin;

    private DocumentMarkdownSourceSpanCandidate sourceSpan;

    private String text;

    private Integer level;

    private String marker;

    private Integer ordinal;

    private Boolean header;

    private String alignment;

    private Integer rowIndex;

    private Integer columnIndex;

    private String info;
}
