package org.smartledge.ai.auth.config;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.stereotype.Component;

/**
 * 线上演示只读模式配置。
 */
@Data
@Component
public class PreviewModeProperties {
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SystemConfigProvider systemConfigProvider;

    /**
     * 是否开启只读展示模式。
     */
    private Boolean enabled;

    /**
     * 只读模式提示语。
     */
    private String message;

    public Boolean getEnabled() { var p = snapshot(); return required(p == null ? enabled : Boolean.valueOf(p.getPreviewMode().isEnabled()), "previewMode.enabled"); }
    public String getMessage() { var p = snapshot(); return required(p == null ? message : p.getPreviewMode().getMessage(), "previewMode.message"); }
    private org.smartledge.ai.manage.model.SystemConfigSnapshot snapshot() {
        return systemConfigProvider == null ? null : systemConfigProvider.currentSnapshot();
    }

    private <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalStateException("Missing required database configuration: " + key);
        }
        return value;
    }
}
