package org.smartledge.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMarkdownSourceSpanCandidate {

    private Integer startByte;

    private Integer endByte;

    private Integer startLine;

    private Integer startColumn;

    private Integer endLine;

    private Integer endColumn;
}
