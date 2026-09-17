package org.smartledge.ai.manage.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Strict parser for the version-1 document metadata filter contract. */
public class DocumentMetadataFilterParser {

    public static final int CURRENT_VERSION = 1;
    public static final int MAX_CONDITIONS = 32;

    private static final Set<String> SUPPORTED_OPERATIONS = Set.of(
        "=", "!=", "contains", "in", "not in", "empty", "not empty"
    );
    private static final Set<String> GROUP_KEYS = Set.of("and", "or");
    private final ObjectMapper objectMapper;

    public DocumentMetadataFilterParser() {
        this(new ObjectMapper());
    }

    public DocumentMetadataFilterParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public DocumentMetadataFilter parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return new DocumentMetadataFilter(CURRENT_VERSION, new DocumentMetadataFilter.Group(true, List.of()), 0);
        }
        try {
            JsonNode root = readSingleJsonValue(rawJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("metadataFilterJson must be a JSON object");
            }
            int version = requiredInt(root, "version");
            if (version != CURRENT_VERSION) {
                throw new IllegalArgumentException("unsupported metadata filter version: " + version);
            }
            ParseResult parsed = parseExpression(root, true);
            if (parsed.conditionCount() <= 0 || parsed.conditionCount() > MAX_CONDITIONS) {
                throw new IllegalArgumentException("metadata filter condition count is outside the supported range");
            }
            return new DocumentMetadataFilter(version, parsed.expression(), parsed.conditionCount());
        }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("metadataFilterJson is invalid JSON", exception);
        }
        catch (IOException exception) {
            throw new IllegalArgumentException("metadataFilterJson is invalid JSON", exception);
        }
    }

    private JsonNode readSingleJsonValue(String rawJson) throws IOException {
        try (JsonParser parser = objectMapper.getFactory().createParser(rawJson)) {
            JsonNode root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("metadataFilterJson must contain one JSON value");
            }
            return root;
        }
    }

    private ParseResult parseExpression(JsonNode node, boolean root) {
        boolean hasAnd = node.has("and");
        boolean hasOr = node.has("or");
        boolean hasField = node.has("field") || node.has("op") || node.has("value");
        int expressionKinds = (hasAnd ? 1 : 0) + (hasOr ? 1 : 0) + (hasField ? 1 : 0);
        if (expressionKinds != 1) {
            throw new IllegalArgumentException("metadata filter expression must be one condition or one logical group");
        }
        if (hasAnd || hasOr) {
            String groupKey = hasAnd ? "and" : "or";
            rejectUnknownKeys(node, root ? Set.of("version", groupKey) : Set.of(groupKey));
            JsonNode children = node.get(groupKey);
            if (children == null || !children.isArray() || children.isEmpty()) {
                throw new IllegalArgumentException("metadata filter logical group must be non-empty");
            }
            List<DocumentMetadataFilter.Expression> expressions = new ArrayList<>();
            int count = 0;
            for (JsonNode child : children) {
                if (child == null || !child.isObject()) {
                    throw new IllegalArgumentException("metadata filter child must be an object");
                }
                ParseResult parsed = parseChild(child);
                count += parsed.conditionCount();
                if (count > MAX_CONDITIONS) {
                    throw new IllegalArgumentException("metadata filter condition count exceeds " + MAX_CONDITIONS);
                }
                expressions.add(parsed.expression());
            }
            return new ParseResult(new DocumentMetadataFilter.Group("and".equals(groupKey), expressions), count);
        }
        rejectUnknownKeys(node, root
            ? Set.of("version", "field", "op", "value")
            : Set.of("field", "op", "value"));
        return parseCondition(node, root);
    }

    private ParseResult parseChild(JsonNode node) {
        if (node.has("and") || node.has("or")) {
            return parseExpression(node, false);
        }
        return parseCondition(node, false);
    }

    private ParseResult parseCondition(JsonNode node, boolean root) {
        rejectUnknownKeys(node, root
            ? Set.of("version", "field", "op", "value")
            : Set.of("field", "op", "value"));
        String field = textRequired(node, "field");
        if (!DocumentMetadataValueSupport.validFieldName(field)) {
            throw new IllegalArgumentException("illegal metadata filter field: " + field);
        }
        String operation = textRequired(node, "op");
        if (!SUPPORTED_OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("unsupported metadata filter operation: " + operation);
        }
        boolean requiresValue = !operation.equals("empty") && !operation.equals("not empty");
        if (requiresValue && !node.has("value")) {
            throw new IllegalArgumentException("metadata filter operation requires a value: " + operation);
        }
        if (!requiresValue && node.has("value")) {
            throw new IllegalArgumentException("empty operations do not accept a value");
        }
        Object expected = requiresValue ? readValue(node.get("value"), operation) : null;
        return new ParseResult(new DocumentMetadataFilter.Condition(field, operation, expected), 1);
    }

    private Object readValue(JsonNode node, String operation) {
        if (operation.equals("contains") && (node == null || !node.isTextual())) {
            throw new IllegalArgumentException("contains requires a string value");
        }
        if ((operation.equals("=") || operation.equals("!="))
            && (node == null || node.isArray())) {
            throw new IllegalArgumentException(operation + " requires a scalar value");
        }
        if ((operation.equals("in") || operation.equals("not in"))
            && (node == null || !node.isArray() || node.isEmpty())) {
            throw new IllegalArgumentException(operation + " requires a non-empty array value");
        }
        if (node == null) {
            throw new IllegalArgumentException("metadata filter value is required");
        }
        if (node.isObject() || (node.isArray() && node.elements().hasNext() && node.elements().next().isArray())) {
            throw new IllegalArgumentException("metadata filter values must be scalar or a flat array");
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode item : node) {
                values.add(readScalar(item));
            }
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
        return readScalar(node);
    }

    private Object readScalar(JsonNode node) {
        if (node == null || node.isObject() || node.isArray()) {
            throw new IllegalArgumentException("metadata filter value must be scalar");
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue().trim();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return new BigDecimal(node.asText());
        }
        throw new IllegalArgumentException("unsupported metadata filter value type");
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalArgumentException("metadata filter version must be an integer");
        }
        return value.intValue();
    }

    private String textRequired(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().trim().isEmpty()) {
            throw new IllegalArgumentException("metadata filter " + field + " must be a non-empty string");
        }
        return value.textValue().trim();
    }

    private void rejectUnknownKeys(JsonNode node, Set<String> allowed) {
        Iterator<String> fields = node.fieldNames();
        Set<String> unknown = new HashSet<>();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                unknown.add(field);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown metadata filter fields: " + unknown);
        }
    }

    private record ParseResult(DocumentMetadataFilter.Expression expression, int conditionCount) {
    }
}
