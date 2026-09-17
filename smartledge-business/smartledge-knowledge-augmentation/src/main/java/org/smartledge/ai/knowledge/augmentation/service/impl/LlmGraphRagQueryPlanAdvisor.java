package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.rag.runtime.port.ObservedChatModelPort;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagQueryCatalog;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagQueryPlanAdvice;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagQueryPlanAdvisor;
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
public class LlmGraphRagQueryPlanAdvisor implements GraphRagQueryPlanAdvisor {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    private final ObservedChatModelPort observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final GraphRagLlmConfigurationPort configurationPort;

    @Autowired
    public LlmGraphRagQueryPlanAdvisor(ObservedChatModelPort observedChatModelService,
                                       PromptTemplateService promptTemplateService,
                                       ObjectMapper objectMapper,
                                       ObjectProvider<GraphRagLlmConfigurationPort> configurationProvider) {
        this(observedChatModelService, promptTemplateService, objectMapper,
            configurationProvider == null ? null : configurationProvider.getIfAvailable());
    }

    public LlmGraphRagQueryPlanAdvisor(ObservedChatModelPort observedChatModelService,
                                       PromptTemplateService promptTemplateService,
                                       ObjectMapper objectMapper) {
        this(observedChatModelService, promptTemplateService, objectMapper,
            (GraphRagLlmConfigurationPort) null);
    }

    private LlmGraphRagQueryPlanAdvisor(ObservedChatModelPort observedChatModelService,
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
    public Optional<GraphRagQueryPlanAdvice> advise(String question, GraphRagQueryCatalog catalog) {
        if (!enabled() || StrUtil.isBlank(question) || catalog == null || catalogEmpty(catalog)) {
            return Optional.empty();
        }
        try {
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_GRAPH_RAG_QUERY_PLAN, Map.of(
                "question", StrUtil.blankToDefault(question, ""),
                "catalog", renderCatalog(catalog)
            ));
            String raw = observedChatModelService.callText(
                "document_graph_rag_query_plan",
                null,
                prompt,
                buildCallOptions(),
                null
            );
            if (StrUtil.isBlank(raw)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(extractJsonObject(raw), GraphRagQueryPlanAdvice.class));
        }
        catch (Exception exception) {
            log.warn("GraphRAG LLM 受控查询计划生成失败: question='{}', message={}",
                question,
                exception.getMessage());
            throw new IllegalStateException("GraphRAG LLM 受控查询计划生成失败", exception);
        }
    }

    private boolean enabled() {
        return configurationPort.queryPlanEnabled();
    }

    private boolean catalogEmpty(GraphRagQueryCatalog catalog) {
        return CollUtil.isEmpty(catalog.getEntities())
            && CollUtil.isEmpty(catalog.getRelations())
            && CollUtil.isEmpty(catalog.getCommunities());
    }

    private ChatCallOptions buildCallOptions() {
        return ChatCallOptions.builder()
            .temperature(0.0D)
            .topP(0.1D)
            .thinking(false)
            .build();
    }

    private String renderCatalog(GraphRagQueryCatalog catalog) throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entities", renderEntities(catalog));
        payload.put("relations", renderRelations(catalog));
        payload.put("communities", renderCommunities(catalog));
        return objectMapper.writeValueAsString(payload);
    }

    private List<Map<String, Object>> renderEntities(GraphRagQueryCatalog catalog) {
        return (catalog.getEntities() == null ? List.<GraphRagQueryCatalog.EntityItem>of() : catalog.getEntities()).stream()
            .filter(entity -> entity != null && entity.getEntityId() != null)
            .map(entity -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("entityId", entity.getEntityId());
                item.put("name", StrUtil.blankToDefault(entity.getName(), ""));
                item.put("normalizedName", StrUtil.blankToDefault(entity.getNormalizedName(), ""));
                item.put("entityType", StrUtil.blankToDefault(entity.getEntityType(), ""));
                item.put("aliases", entity.getAliases() == null ? List.of() : entity.getAliases());
                item.put("description", StrUtil.maxLength(StrUtil.blankToDefault(entity.getDescription(), ""), 160));
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> renderRelations(GraphRagQueryCatalog catalog) {
        return (catalog.getRelations() == null ? List.<GraphRagQueryCatalog.RelationItem>of() : catalog.getRelations()).stream()
            .filter(relation -> relation != null && relation.getRelationId() != null)
            .map(relation -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("relationId", relation.getRelationId());
                item.put("relationType", StrUtil.blankToDefault(relation.getRelationType(), ""));
                item.put("sourceEntityId", relation.getSourceEntityId());
                item.put("sourceEntityName", StrUtil.blankToDefault(relation.getSourceEntityName(), ""));
                item.put("targetEntityId", relation.getTargetEntityId());
                item.put("targetEntityName", StrUtil.blankToDefault(relation.getTargetEntityName(), ""));
                item.put("description", StrUtil.maxLength(StrUtil.blankToDefault(relation.getDescription(), ""), 180));
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> renderCommunities(GraphRagQueryCatalog catalog) {
        return (catalog.getCommunities() == null ? List.<GraphRagQueryCatalog.CommunityItem>of() : catalog.getCommunities()).stream()
            .filter(community -> community != null && community.getCommunityId() != null)
            .map(community -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("communityId", community.getCommunityId());
                item.put("title", StrUtil.blankToDefault(community.getTitle(), ""));
                item.put("summary", StrUtil.maxLength(StrUtil.blankToDefault(community.getSummary(), ""), 220));
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
