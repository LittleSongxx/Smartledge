package org.smartledge.ai.manage.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the document-owned metadata JSON object into scalar/array values. */
public class DocumentMetadataJsonParser {

    private final ObjectMapper objectMapper;

    public DocumentMetadataJsonParser() {
        this(new ObjectMapper());
    }

    public DocumentMetadataJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public Map<String, Object> parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = readSingleJsonValue(rawJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("document metadata must be a JSON object");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!DocumentMetadataValueSupport.validFieldName(field.getKey())) {
                    throw new IllegalArgumentException("illegal document metadata field: " + field.getKey());
                }
                values.put(field.getKey(), readValue(field.getValue()));
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("document metadata JSON is invalid", exception);
        }
        catch (IOException exception) {
            throw new IllegalArgumentException("document metadata JSON is invalid", exception);
        }
    }

    private JsonNode readSingleJsonValue(String rawJson) throws IOException {
        try (JsonParser parser = objectMapper.getFactory().createParser(rawJson)) {
            JsonNode root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("document metadata must contain one JSON value");
            }
            return root;
        }
    }

    private Object readValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue().trim();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return new BigDecimal(node.asText());
        }
        if (node.isFloatingPointNumber() || node.isBigDecimal()) {
            return node.decimalValue();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && (item.isArray() || item.isObject())) {
                    throw new IllegalArgumentException("document metadata arrays must contain scalar values");
                }
                values.add(readValue(item));
            }
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
        throw new IllegalArgumentException("document metadata values must be scalar or arrays");
    }
}
