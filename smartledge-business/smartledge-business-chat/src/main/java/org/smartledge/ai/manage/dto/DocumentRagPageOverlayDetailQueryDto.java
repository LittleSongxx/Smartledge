package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DocumentRagPageOverlayDetailQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    private Long parseTaskId;

    @NotNull(message = "页码不能为空")
    @Min(value = 0, message = "页码不能小于0")
    private Integer pageNo;
}
