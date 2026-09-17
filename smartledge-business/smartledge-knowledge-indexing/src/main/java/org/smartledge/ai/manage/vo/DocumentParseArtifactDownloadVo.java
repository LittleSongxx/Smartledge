package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseArtifactDownloadVo {

    private String fileName;

    private String contentType;

    private Long size;

    private byte[] bytes;
}
