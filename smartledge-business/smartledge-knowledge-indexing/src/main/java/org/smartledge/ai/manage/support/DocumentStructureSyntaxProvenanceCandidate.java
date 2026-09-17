package org.smartledge.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStructureSyntaxProvenanceCandidate {

    private String schemaVersion;

    private String sourceSha256;

    private String syntaxNodeId;

    private String syntaxNodeType;

    private String sourceOrigin;

    private DocumentMarkdownSourceSpanCandidate sourceSpan;
}
