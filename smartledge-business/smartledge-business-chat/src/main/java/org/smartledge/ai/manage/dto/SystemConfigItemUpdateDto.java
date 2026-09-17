package org.smartledge.ai.manage.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SystemConfigItemUpdateDto {

    @NotBlank(message = "配置键不能为空")
    @Size(max = 128, message = "配置键长度不能超过128")
    private String configKey;

    @NotNull(message = "配置值不能为空")
    private JsonNode value;

    @NotNull(message = "期望版本不能为空")
    @Min(value = 0, message = "期望版本不能小于0")
    private Integer expectedVersion;

    @NotBlank(message = "修改说明不能为空")
    @Size(max = 255, message = "修改说明长度不能超过255")
    private String changeNote;
}
