package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DocumentParseArtifactContentQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    private Long taskId;

    @NotNull(message = "解析产物id不能为空")
    private Long artifactId;
}
