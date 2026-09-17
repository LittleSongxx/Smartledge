package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseArtifactContentVo {

    private DocumentParseArtifactItemVo artifact;

    private String content;

    private Long contentLength;

    private Boolean json;

    private Boolean markdown;

    private Boolean image;

    private String imageBase64;

    private String dataUrl;
}
