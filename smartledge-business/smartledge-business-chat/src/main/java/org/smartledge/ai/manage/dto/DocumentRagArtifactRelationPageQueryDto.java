package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class DocumentRagArtifactRelationPageQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    @NotNull(message = "解析任务id不能为空")
    private Long parseTaskId;

    @NotNull(message = "索引任务id不能为空")
    private Long indexTaskId;

    @NotBlank(message = "节点id不能为空")
    @Size(max = 80, message = "节点id长度不能超过80")
    private String nodeId;

    @NotBlank(message = "关系方向不能为空")
    @Size(max = 16, message = "关系方向长度不能超过16")
    private String direction;

    @Size(max = 20, message = "关系类型过滤不能超过20个")
    private List<@Size(max = 64, message = "关系类型长度不能超过64") String> relationTypes;

    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNo;

    @Min(value = 1, message = "每页数量不能小于1")
    @Max(value = 20, message = "每页数量不能超过20")
    private Integer pageSize;
}
