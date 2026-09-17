package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SystemConfigHistoryRestoreDto {

    @NotNull(message = "历史记录id不能为空")
    private Long historyId;

    @NotNull(message = "期望版本不能为空")
    @Min(value = 0, message = "期望版本不能小于0")
    private Integer expectedVersion;

    @Size(max = 255, message = "恢复说明长度不能超过255")
    private String changeNote;
}
