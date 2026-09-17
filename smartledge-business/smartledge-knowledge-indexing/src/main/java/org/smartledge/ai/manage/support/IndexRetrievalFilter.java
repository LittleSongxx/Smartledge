package org.smartledge.ai.manage.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 索引侧预过滤规则：当前世代、未过期、已校验用户标量。
 */
public final class IndexRetrievalFilter {

    public static final String USER_METADATA_PREFIX = "user.";
    public static final String VECTOR_CURRENT_AND_FRESH_SQL =
        "status = 1 AND (expires_at IS NULL OR expires_at > NOW())";

    private IndexRetrievalFilter() {
    }

    public static boolean currentAndNotExpired(Integer status, Instant expiresAt, Instant now) {
        if (status == null || status != 1) {
            return false;
        }
        Instant clock = now == null ? Instant.now() : now;
        return expiresAt == null || expiresAt.isAfter(clock);
    }

    public static Map<String, Object> flattenUserScalars(Map<String, Object> parsedMetadata) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        if (parsedMetadata == null || parsedMetadata.isEmpty()) {
            return flattened;
        }
        parsedMetadata.forEach((key, value) -> {
            if (key == null || key.isBlank() || !DocumentMetadataValueSupport.validFieldName(key)) {
                return;
            }
            if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                return;
            }
            flattened.put(USER_METADATA_PREFIX + key, value);
        });
        return flattened;
    }

    public static boolean matchesUserScalars(Map<String, Object> indexPayload, Map<String, Object> requiredEquals) {
        if (requiredEquals == null || requiredEquals.isEmpty()) {
            return true;
        }
        if (indexPayload == null || indexPayload.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Object> required : requiredEquals.entrySet()) {
            String key = required.getKey();
            if (key == null || key.isBlank()) {
                return false;
            }
            String payloadKey = key.startsWith(USER_METADATA_PREFIX) ? key : USER_METADATA_PREFIX + key;
            if (!DocumentMetadataValueSupport.equalsValue(indexPayload.get(payloadKey), required.getValue())) {
                return false;
            }
        }
        return true;
    }

    public static String userMetadataSqlEquals(String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        String jsonKey = fieldName.startsWith(USER_METADATA_PREFIX) ? fieldName : USER_METADATA_PREFIX + fieldName;
        return "metadata_json ->> '" + jsonKey.replace("'", "''") + "' = ?";
    }
}
