package org.smartledge.ai.manage.support;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

final class DocumentMetadataValueSupport {

    private DocumentMetadataValueSupport() {
    }

    static boolean validFieldName(String field) {
        return field != null && field.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    }

    static Object normalize(Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text) {
            return text.trim();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(DocumentMetadataValueSupport::normalize).toList();
        }
        return value;
    }

    static boolean equalsValue(Object left, Object right) {
        Object normalizedLeft = normalize(left);
        Object normalizedRight = normalize(right);
        if (normalizedLeft instanceof BigDecimal leftNumber && normalizedRight instanceof BigDecimal rightNumber) {
            return leftNumber.compareTo(rightNumber) == 0;
        }
        return Objects.equals(normalizedLeft, normalizedRight);
    }

    static boolean empty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.trim().isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        return false;
    }

    static List<?> asValues(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().toList();
        }
        return value == null ? java.util.Collections.singletonList(null) : List.of(value);
    }
}
