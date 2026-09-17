package org.smartledge.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMarkdownSyntaxCandidate {

    private String schemaVersion;

    private String sourceOrigin;

    private String sourceText;

    private Integer sourceLengthBytes;

    private String sourceSha256;

    private List<DocumentMarkdownSyntaxNodeCandidate> nodes = new ArrayList<>();
}
