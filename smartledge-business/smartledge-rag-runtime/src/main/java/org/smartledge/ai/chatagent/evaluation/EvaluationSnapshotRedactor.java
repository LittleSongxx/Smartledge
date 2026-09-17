package org.smartledge.ai.chatagent.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
final class EvaluationSnapshotRedactor {

    static final String REDACTED = "[REDACTED]";

    private static final Pattern EMAIL = Pattern.compile("(?i)(?<![\\w.+-])[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}(?![\\w.-])");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/-]+=*");
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
        "password", "passwd", "secret", "token", "credential", "accesskey", "privatekey",
        "jdbcurl", "connection", "endpoint", "hostname", "host", "uri"
    );

    String redactText(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String redacted = EMAIL.matcher(value).replaceAll(REDACTED);
        redacted = PHONE.matcher(redacted).replaceAll(REDACTED);
        return BEARER.matcher(redacted).replaceAll("Bearer " + REDACTED);
    }

    Map<String, Object> sanitizeMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, sanitizeValue(key, value)));
        // Map.copyOf rejects null values, but optional diagnostics/config fields may be null.
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(String key, Object value) {
        if (isSensitiveKey(key)) {
            return REDACTED;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> stringMap = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> stringMap.put(String.valueOf(nestedKey), nestedValue));
            return sanitizeMap(stringMap);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(sanitizeValue("", item));
            }
            // List.copyOf has the same null restriction; preserve the source shape for snapshots.
            return Collections.unmodifiableList(result);
        }
        if (value instanceof String text) {
            return redactText(text);
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key == null
            ? ""
            : key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(".", "");
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }
}
