package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.rag.model.DocumentTableQueryPlanAdvice;
import org.smartledge.ai.chatagent.service.ObservedChatModelService;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableDescriptor;
import org.smartledge.ai.prompt.PromptTemplateNames;
import org.smartledge.ai.prompt.PromptTemplateService;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class LlmDocumentTableQueryPlanAdvisor implements DocumentTableQueryPlanAdvisor {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    private final ObservedChatModelService observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    public LlmDocumentTableQueryPlanAdvisor(ObservedChatModelService observedChatModelService,
                                            PromptTemplateService promptTemplateService,
                                            ObjectMapper objectMapper) {
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
    }

    @Override
    public java.util.Optional<DocumentTableQueryPlanAdvice> advise(String question, List<DocumentTableDescriptor> tables) {
        if (StrUtil.isBlank(question) || tables == null || tables.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_TABLE_QUERY_PLAN, Map.of(
                "question", StrUtil.blankToDefault(question, ""),
                "tables", renderTableCatalog(tables)
            ));
            String raw = observedChatModelService.callText(
                "document_table_query_plan",
                null,
                prompt,
                buildCallOptions(),
                null
            );
            if (StrUtil.isBlank(raw)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(objectMapper.readValue(extractJsonObject(raw), DocumentTableQueryPlanAdvice.class));
        }
        catch (Exception exception) {
            log.warn("表格查询 LLM 受控计划生成失败: question='{}', message={}",
                question,
                exception.getMessage());
            throw new IllegalStateException("表格查询 LLM 受控计划生成失败", exception);
        }
    }

    private ChatCallOptions buildCallOptions() {
        return ChatCallOptions.builder()
            .temperature(0.0D)
            .topP(0.1D)
            .thinking(false)
            .build();
    }

    private String renderTableCatalog(List<DocumentTableDescriptor> tables) throws com.fasterxml.jackson.core.JsonProcessingException {
        List<Map<String, Object>> catalog = tables.stream()
            .filter(table -> table != null && table.getTableId() != null)
            .map(table -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tableId", table.getTableId());
                item.put("tableNo", table.getTableNo());
                item.put("title", StrUtil.blankToDefault(table.getTitle(), ""));
                item.put("sectionPath", StrUtil.blankToDefault(table.getSectionPath(), ""));
                item.put("columns", renderColumns(table));
                return item;
            })
            .toList();
        return objectMapper.writeValueAsString(catalog);
    }

    private List<Map<String, Object>> renderColumns(DocumentTableDescriptor table) {
        return (table.getColumns() == null ? List.<DocumentTableDescriptor.Column>of() : table.getColumns()).stream()
            .map(column -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("columnNo", column.getColumnNo());
                item.put("name", StrUtil.blankToDefault(column.getColumnName(), ""));
                item.put("normalizedName", StrUtil.blankToDefault(column.getNormalizedName(), ""));
                item.put("valueType", StrUtil.blankToDefault(column.getValueType(), ""));
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
