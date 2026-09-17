package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemConfigChangeItemVo {

    private String configKey;
    private String label;
    private Object beforeValue;
    private Object afterValue;
    private String valueType;
    private String controlType;
    private Integer displayScale;
    private String unit;
}
