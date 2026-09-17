package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DocumentRagArtifactTableWindowQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    @NotNull(message = "解析任务id不能为空")
    private Long parseTaskId;

    @NotNull(message = "索引任务id不能为空")
    private Long indexTaskId;

    @NotBlank(message = "表格节点id不能为空")
    @Size(max = 80, message = "表格节点id长度不能超过80")
    private String tableNodeId;

    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNo;

    @Min(value = 1, message = "每页数量不能小于1")
    @Max(value = 50, message = "每页数量不能超过50")
    private Integer pageSize;

    @Min(value = 0, message = "列偏移不能小于0")
    private Integer columnOffset;

    @Min(value = 1, message = "列窗口不能小于1")
    @Max(value = 50, message = "列窗口不能超过50")
    private Integer columnLimit;
}
