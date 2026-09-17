package org.smartledge.ai.chatagent.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * @description: 支撑组件
 * @author: Song
 **/

@Component
public class TavilyToolArguments {


    private final ObjectMapper objectMapper;

    public TavilyToolArguments(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String normalizeArguments(String arguments, String fallbackQuery) {
        if (StrUtil.isBlank(arguments)) {
            return buildQueryPayload(fallbackQuery);
        }

        try {
            JsonNode rootNode = objectMapper.readTree(arguments);

            if (rootNode != null && rootNode.isObject()) {
                ObjectNode objectNode = ((ObjectNode) rootNode).deepCopy();
                if (StrUtil.isNotBlank(objectNode.path("query").asText())) {
                    return arguments;
                }
                if (StrUtil.isBlank(fallbackQuery)) {
                    return null;
                }
                objectNode.put("query", fallbackQuery);
                return objectMapper.writeValueAsString(objectNode);
            }

            if (rootNode != null && rootNode.isTextual() && StrUtil.isNotBlank(rootNode.asText())) {
                return buildQueryPayload(rootNode.asText().trim());
            }

            return buildQueryPayload(fallbackQuery);
        }
        catch (JsonProcessingException exception) {

            if (StrUtil.isNotBlank(arguments)) {
                return buildQueryPayload(arguments.trim());
            }
            return buildQueryPayload(fallbackQuery);
        }
    }

    private String buildQueryPayload(String query) {
        if (StrUtil.isBlank(query)) {
            return null;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("query", query.trim());

        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("构造 tavily_search 入参 JSON 失败", exception);
        }
    }
}
