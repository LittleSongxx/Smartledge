package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.rag.runtime.port.ObservedChatModelPort;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEntityResolutionAdvice;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEntityResolutionContext;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagEntityResolutionAdvisor;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagLlmConfigurationPort;
import org.smartledge.ai.prompt.PromptTemplateNames;
import org.smartledge.ai.prompt.PromptTemplateService;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class LlmGraphRagEntityResolutionAdvisor implements GraphRagEntityResolutionAdvisor {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    private final ObservedChatModelPort observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final GraphRagLlmConfigurationPort configurationPort;

    @Autowired
    public LlmGraphRagEntityResolutionAdvisor(ObservedChatModelPort observedChatModelService,
                                              PromptTemplateService promptTemplateService,
                                              ObjectMapper objectMapper,
                                              ObjectProvider<GraphRagLlmConfigurationPort> configurationProvider) {
        this(observedChatModelService, promptTemplateService, objectMapper,
            configurationProvider == null ? null : configurationProvider.getIfAvailable());
    }

    public LlmGraphRagEntityResolutionAdvisor(ObservedChatModelPort observedChatModelService,
                                              PromptTemplateService promptTemplateService,
                                              ObjectMapper objectMapper) {
        this(observedChatModelService, promptTemplateService, objectMapper,
            (GraphRagLlmConfigurationPort) null);
    }

    private LlmGraphRagEntityResolutionAdvisor(ObservedChatModelPort observedChatModelService,
                                               PromptTemplateService promptTemplateService,
                                               ObjectMapper objectMapper,
                                               GraphRagLlmConfigurationPort configurationPort) {
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
        this.configurationPort = java.util.Objects.requireNonNull(configurationPort,
            "GraphRAG configuration provider is required");
    }

    @Override
    public Optional<GraphRagEntityResolutionAdvice> advise(GraphRagEntityResolutionContext context) {
        if (!enabled() || context == null || context.getEntities() == null || context.getEntities().size() < 2) {
            return Optional.empty();
        }
        try {
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_GRAPH_RAG_ENTITY_RESOLUTION, Map.of(
                "context", renderContext(context)
            ));
            String raw = observedChatModelService.callText(
                "document_graph_rag_entity_resolution",
                null,
                prompt,
                buildCallOptions(),
                null
            );
            if (StrUtil.isBlank(raw)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(extractJsonObject(raw), GraphRagEntityResolutionAdvice.class));
        }
        catch (Exception exception) {
            log.warn("GraphRAG LLM 实体消歧建议生成失败: message={}", exception.getMessage());
            throw new IllegalStateException("GraphRAG LLM 实体消歧建议生成失败", exception);
        }
    }

    private boolean enabled() {
        return configurationPort.entityResolutionEnabled();
    }

    private ChatCallOptions buildCallOptions() {
        return ChatCallOptions.builder()
            .temperature(0.0D)
            .topP(0.1D)
            .thinking(false)
            .build();
    }

    private String renderContext(GraphRagEntityResolutionContext context) throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entities", renderEntities(context));
        return objectMapper.writeValueAsString(payload);
    }

    private List<Map<String, Object>> renderEntities(GraphRagEntityResolutionContext context) {
        return (context.getEntities() == null ? List.<GraphRagEntityResolutionContext.EntityItem>of() : context.getEntities()).stream()
            .filter(entity -> entity != null && StrUtil.isNotBlank(entity.getSourceEntityId()))
            .map(entity -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sourceEntityId", entity.getSourceEntityId());
                item.put("name", StrUtil.blankToDefault(entity.getName(), ""));
                item.put("normalizedName", StrUtil.blankToDefault(entity.getNormalizedName(), ""));
                item.put("entityType", StrUtil.blankToDefault(entity.getEntityType(), ""));
                item.put("aliases", entity.getAliases() == null ? List.of() : entity.getAliases());
                item.put("description", StrUtil.maxLength(StrUtil.blankToDefault(entity.getDescription(), ""), 220));
                item.put("confidence", entity.getConfidence());
                item.put("sourceChunkIds", entity.getSourceChunkIds() == null ? List.of() : entity.getSourceChunkIds());
                item.put("evidenceIds", entity.getEvidenceIds() == null ? List.of() : entity.getEvidenceIds());
                return item;
            })
            .toList();
    }

    private String extractJsonObject(String raw) {
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(raw.trim());
        if (matcher.find()) {
            return matcher.group();
        }
        return raw.trim();
    }
}
