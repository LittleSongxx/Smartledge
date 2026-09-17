package org.smartledge.ai.manage.support;

import org.smartledge.ai.manage.model.DocumentMetadata;

import java.util.List;
import java.util.Map;

/** Immutable, versioned document-metadata condition model evaluated at the scope seam. */
public final class DocumentMetadataFilter {

    private final int version;
    private final Expression expression;
    private final int conditionCount;

    DocumentMetadataFilter(int version, Expression expression, int conditionCount) {
        this.version = version;
        this.expression = expression;
        this.conditionCount = conditionCount;
    }

    public int version() {
        return version;
    }

    public int conditionCount() {
        return conditionCount;
    }

    public boolean matches(DocumentMetadata metadata) {
        return metadata != null && metadata.valid() && expression.matches(metadata.values());
    }

    /** Convenience seam for deterministic evaluator tests and non-persistent metadata fixtures. */
    public boolean matches(Map<String, Object> metadata) {
        return matches(new DocumentMetadata(null, metadata, true));
    }

    interface Expression {
        boolean matches(Map<String, Object> metadata);
    }

    static final class Group implements Expression {
        private final boolean and;
        private final List<Expression> children;

        Group(boolean and, List<Expression> children) {
            this.and = and;
            this.children = List.copyOf(children);
        }

        @Override
        public boolean matches(Map<String, Object> metadata) {
            if (and) {
                return children.stream().allMatch(child -> child.matches(metadata));
            }
            return children.stream().anyMatch(child -> child.matches(metadata));
        }
    }

    static final class Condition implements Expression {
        private final String field;
        private final String operation;
        private final Object expected;

        Condition(String field, String operation, Object expected) {
            this.field = field;
            this.operation = operation;
            this.expected = expected;
        }

        @Override
        public boolean matches(Map<String, Object> metadata) {
            boolean present = metadata != null && metadata.containsKey(field);
            Object actual = present ? metadata.get(field) : null;
            return switch (operation) {
                case "empty" -> !present || DocumentMetadataValueSupport.empty(actual);
                case "not empty" -> present && !DocumentMetadataValueSupport.empty(actual);
                case "=" -> present && anyEquals(actual, expected);
                case "!=" -> present && actual != null && (expected == null || !anyEquals(actual, expected));
                case "contains" -> present && contains(actual, expected);
                case "in" -> present && intersects(actual, expected);
                case "not in" -> present && actual != null && !intersects(actual, expected);
                default -> false;
            };
        }

        private boolean anyEquals(Object actual, Object expected) {
            return DocumentMetadataValueSupport.asValues(actual).stream()
                .anyMatch(value -> DocumentMetadataValueSupport.equalsValue(value, expected));
        }

        private boolean contains(Object actual, Object expected) {
            if (!(expected instanceof String needle)) {
                return false;
            }
            if (actual instanceof String text) {
                return text.trim().contains(needle);
            }
            if (actual instanceof java.util.Collection<?> values) {
                return values.stream().anyMatch(value -> value instanceof String text && text.trim().contains(needle));
            }
            return false;
        }

        private boolean intersects(Object actual, Object expected) {
            if (!(expected instanceof List<?> expectedValues)) {
                return false;
            }
            return DocumentMetadataValueSupport.asValues(actual).stream()
                .anyMatch(value -> expectedValues.stream().anyMatch(expectedValue ->
                    DocumentMetadataValueSupport.equalsValue(value, expectedValue)));
        }
    }
}
