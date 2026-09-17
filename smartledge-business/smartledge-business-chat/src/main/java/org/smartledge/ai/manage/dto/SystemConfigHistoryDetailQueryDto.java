package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SystemConfigHistoryDetailQueryDto {

    @NotNull(message = "历史记录id不能为空")
    private Long historyId;
}
