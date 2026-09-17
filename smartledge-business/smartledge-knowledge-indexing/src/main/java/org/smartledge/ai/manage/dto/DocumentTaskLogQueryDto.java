package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/

@Data
public class DocumentTaskLogQueryDto {

    @NotNull(message = "任务id不能为空")
    private Long taskId;

    private Integer pageNo;

    private Integer pageSize;
}
