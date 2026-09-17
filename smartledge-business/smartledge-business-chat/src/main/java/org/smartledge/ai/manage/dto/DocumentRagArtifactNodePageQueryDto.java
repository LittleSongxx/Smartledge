package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DocumentRagArtifactNodePageQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    @NotNull(message = "解析任务id不能为空")
    private Long parseTaskId;

    @NotNull(message = "索引任务id不能为空")
    private Long indexTaskId;

    @NotBlank(message = "产物类型不能为空")
    @Size(max = 32, message = "产物类型长度不能超过32")
    private String nodeType;

    @Size(max = 100, message = "搜索关键词长度不能超过100")
    private String keyword;

    private Boolean rootOnly;

    private Long parentNodeId;

    @Min(value = 0, message = "层级不能小于0")
    private Integer level;

    @Size(max = 64, message = "实体类型长度不能超过64")
    private String entityType;

    private Long entityId;

    private Long relationId;

    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNo;

    @Min(value = 1, message = "每页数量不能小于1")
    @Max(value = 50, message = "每页数量不能超过50")
    private Integer pageSize;
}
