package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemConfigCurrentVo {

    private Integer configVersion;
    private Integer instanceStartVersion;
    private Integer pendingRestartCount;
    private String sourceType;
    private String sourceTypeLabel;
    private Date lastModifiedAt;
    private List<Category> categories;

    public Item findItem(String configKey) {
        if (categories == null) {
            return null;
        }
        return categories.stream()
            .flatMap(category -> category.getItems().stream())
            .filter(item -> configKey.equals(item.getConfigKey()))
            .findFirst()
            .orElse(null);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Category {
        private String groupKey;
        private String groupLabel;
        private String groupDescription;
        private String categoryKey;
        private String categoryLabel;
        private String description;
        private List<Item> items;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String configKey;
        private String label;
        private String description;
        private String relationHint;
        private List<String> relatedConfigKeys;
        private Object value;
        private Object effectiveValue;
        private Boolean pendingRestart;
        private String valueType;
        private String controlType;
        private BigDecimal minValue;
        private BigDecimal maxValue;
        private BigDecimal step;
        private Integer displayScale;
        private String unit;
        private Integer maxLength;
        private String effectiveMode;
        private String effectiveModeLabel;
    }
}
