package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DocumentParseRouteProgressQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    private Long taskId;

    private Long sinceLogId;

    private Integer logLimit;
}
