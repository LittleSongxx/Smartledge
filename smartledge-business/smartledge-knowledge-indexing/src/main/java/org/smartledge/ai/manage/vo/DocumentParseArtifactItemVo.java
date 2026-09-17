package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseArtifactItemVo {

    private Long artifactId;

    private Long documentId;

    private Long taskId;

    private String artifactType;

    private String artifactTypeName;

    private String parserName;

    private String parserVersion;

    private String objectName;

    private String fileName;

    private String contentHash;

    private Long size;

    private String contentType;

    private Boolean viewable;

    private Date createTime;
}
