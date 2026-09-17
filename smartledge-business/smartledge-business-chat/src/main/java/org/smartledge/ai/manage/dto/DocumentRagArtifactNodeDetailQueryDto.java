package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DocumentRagArtifactNodeDetailQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    @NotNull(message = "解析任务id不能为空")
    private Long parseTaskId;

    @NotNull(message = "索引任务id不能为空")
    private Long indexTaskId;

    @NotBlank(message = "节点id不能为空")
    @Size(max = 80, message = "节点id长度不能超过80")
    private String nodeId;
}
