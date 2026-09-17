package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DocumentRagArtifactGraphWindowQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    @NotNull(message = "解析任务id不能为空")
    private Long parseTaskId;

    @NotNull(message = "索引任务id不能为空")
    private Long indexTaskId;

    @Min(value = 1, message = "图谱节点预算不能小于1")
    @Max(value = 300, message = "图谱节点预算不能超过300")
    private Integer maxNodes;

    @Min(value = 1, message = "图谱关系预算不能小于1")
    @Max(value = 500, message = "图谱关系预算不能超过500")
    private Integer maxEdges;

    private Boolean includeIsolates;
}
