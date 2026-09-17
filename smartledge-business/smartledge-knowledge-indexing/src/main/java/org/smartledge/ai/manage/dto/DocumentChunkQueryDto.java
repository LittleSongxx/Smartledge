package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/

@Data
public class DocumentChunkQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    private Long taskId;

    private Integer pageNo;

    private Integer pageSize;
}
