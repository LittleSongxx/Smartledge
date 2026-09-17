package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DocumentRagParseWorkbenchQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    private Long parseTaskId;
}
