package org.smartledge.ai.rag.runtime.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Persistence-neutral structure node view used by navigation consumers. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStructureNode {

    private Long id;
    private Long documentId;
    private Long parseTaskId;
    private Integer nodeNo;
    private Integer nodeType;
    private Long parentNodeId;
    private Long prevSiblingNodeId;
    private Long nextSiblingNodeId;
    private Integer depth;
    private String nodeCode;
    private String title;
    private String anchorText;
    private String canonicalPath;
    private String sectionPath;
    private String contentText;
    private Integer itemIndex;
    private String syntaxSchemaVersion;
    private String syntaxSourceSha256;
    private String syntaxNodeId;
    private String syntaxNodeType;
    private String syntaxSourceOrigin;
    private Integer sourceStartByte;
    private Integer sourceEndByte;
    private Integer sourceStartLine;
    private Integer sourceStartColumn;
    private Integer sourceEndLine;
    private Integer sourceEndColumn;
}
