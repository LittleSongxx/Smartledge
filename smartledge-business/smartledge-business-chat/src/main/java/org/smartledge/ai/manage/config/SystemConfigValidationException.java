package org.smartledge.ai.manage.config;

import org.smartledge.enums.BaseCode;
import org.smartledge.exception.SuperAgentFrameException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable validation facts for administrator-facing configuration guidance. */
public final class SystemConfigValidationException extends SuperAgentFrameException {

    private final String ruleId;
    private final List<String> configKeys;
    private final Map<String, Object> values;
    private final String constraint;
    private final List<String> suggestions;

    public SystemConfigValidationException(String ruleId, String message, List<String> configKeys,
            Map<String, Object> values, String constraint, List<String> suggestions) {
        this(BaseCode.PARAMETER_ERROR.getCode(), ruleId, message, configKeys, values, constraint, suggestions);
    }

    public SystemConfigValidationException(int code, String ruleId, String message, List<String> configKeys,
            Map<String, Object> values, String constraint, List<String> suggestions) {
        super(code, message);
        this.ruleId = ruleId;
        this.configKeys = configKeys == null ? List.of() : List.copyOf(configKeys).stream().limit(12).toList();
        LinkedHashMap<String, Object> safeValues = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (safeValues.size() < 12 && this.configKeys.contains(key)) {
                    safeValues.put(key, value);
                }
            });
        }
        this.values = Map.copyOf(safeValues);
        this.constraint = constraint;
        this.suggestions = suggestions == null ? List.of() : suggestions.stream().limit(5).toList();
    }

    public String ruleId() {
        return ruleId;
    }

    public List<String> configKeys() {
        return configKeys;
    }

    public Map<String, Object> values() {
        return values;
    }

    public String constraint() {
        return constraint;
    }

    public List<String> suggestions() {
        return suggestions;
    }
}
