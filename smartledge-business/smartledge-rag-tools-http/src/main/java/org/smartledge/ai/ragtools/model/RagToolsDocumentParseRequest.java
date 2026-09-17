package org.smartledge.ai.ragtools.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagToolsDocumentParseRequest {

    private String fileName;

    private String mimeType;

    private String fileType;

    private String contentBase64;
}
