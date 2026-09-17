package org.smartledge.ai.manage.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.smartledge.ai.prompt.PromptTemplateNames;
import org.smartledge.ai.prompt.PromptTemplateService;
import org.smartledge.ai.rag.runtime.port.ObservedChatModelPort;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 构建期文本块关键词与问题增强 advisor。
 *
 * <p>照 {@code LlmDocumentTableQueryPlanAdvisor} 模式：LLM 只输出受控 JSON，
 * Java 做数量上限、去空、去重、长度上限校验，注入式内容剔除。生成结果仅覆盖构建期 chunk 的 keywords/questions
 * 检索增强字段，不决定任何检索/融合/final 流程。由数据库配置 {@code chunkEnrichment.enabled} 控制；关闭或调用失败时
 * 调用方保留既有启发式值（错误隔离，非旧链路兜底）。</p>
 */
@Slf4j
@Component
public class LlmChunkKeywordQuestionAdvisor {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");
    private static final List<String> INJECTION_MARKERS = List.of("ignore previous", "ignore the above",
            "system prompt", "you are ", "请忽略", "忽略以上", "忽略上述", "你是一个", "作为ai", "作为 ai");
    private static final int DEFAULT_MAX_KEYWORDS = 8;
    private static final int DEFAULT_MAX_QUESTIONS = 4;
    private static final int DEFAULT_MAX_KEYWORD_CHARS = 32;
    private static final int DEFAULT_MAX_QUESTION_CHARS = 40;
    private static final int DEFAULT_BATCH_CHUNK_LIMIT = 6;
    private static final int DEFAULT_BATCH_TOKEN_LIMIT = 2400;
    private static final int CHUNK_TEXT_CHAR_LIMIT = 1200;
    private static final int CHUNK_METADATA_TOKEN_OVERHEAD = 40;

    @Autowired(required = false)
    private SystemConfigProvider systemConfigProvider;

    private final ObservedChatModelPort observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final ChunkEnrichmentBatchExecutor batchExecutor;

    public LlmChunkKeywordQuestionAdvisor(ObservedChatModelPort observedChatModelService,
            PromptTemplateService promptTemplateService, ObjectMapper objectMapper,
            ChunkEnrichmentBatchExecutor batchExecutor) {
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
        this.batchExecutor = batchExecutor;
    }

    /**
     * 为一批切块生成 keywords/questions 检索增强字段。
     *
     * @param items 待增强切块（ref 为调用方分配的稳定引用，用于回填）
     * @return 仅包含成功生成且通过 Java 校验的切块，key 为入参 ref；其余切块不出现在结果里，调用方保留既有启发式值
     */
    public Map<String, ChunkKeywordQuestion> enrich(Long documentId, Long taskId, List<ChunkEnrichmentItem> items) {
        Map<String, ChunkKeywordQuestion> result = new LinkedHashMap<>();
        if (!options().isEnabled() || items == null || items.isEmpty()) {
            return result;
        }
        List<ChunkEnrichmentItem> validItems = items.stream()
                .filter(item -> item != null && StrUtil.isNotBlank(item.ref()) && StrUtil.isNotBlank(item.text()))
                .toList();
        if (validItems.isEmpty()) {
            return result;
        }
        List<Map<String, ChunkKeywordQuestion>> completed = batchExecutor.execute(documentId, taskId,
                buildBatches(validItems), this::callBatch);
        for (Map<String, ChunkKeywordQuestion> batchResult : completed) {
            result.putAll(batchResult);
        }
        return result;
    }

    private Map<String, ChunkKeywordQuestion> callBatch(List<ChunkEnrichmentItem> batch) throws Exception {
        String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_CHUNK_KEYWORD_QUESTION,
                Map.of("chunks", renderChunks(batch), "maxKeywords", effectiveMaxKeywords(), "maxQuestions",
                        effectiveMaxQuestions()));
        String raw = observedChatModelService.callText("document_chunk_keyword_question", null, prompt,
                buildCallOptions(), null);
        if (StrUtil.isBlank(raw)) {
            return Map.of();
        }
        JsonNode response = objectMapper.readTree(extractJsonObject(raw));
        return normalizeResponse(batch, response);
    }

    private Map<String, ChunkKeywordQuestion> normalizeResponse(List<ChunkEnrichmentItem> batch, JsonNode response) {
        Map<String, ChunkKeywordQuestion> normalized = new LinkedHashMap<>();
        JsonNode chunks = response == null || !response.isObject() ? null : response.get("chunks");
        if (chunks == null || !chunks.isArray()) {
            return normalized;
        }
        LinkedHashSet<String> requestedRefs = new LinkedHashSet<>();
        for (ChunkEnrichmentItem item : batch) {
            requestedRefs.add(item.ref());
        }
        for (JsonNode item : chunks) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String ref = readChunkRef(item.get("chunkRef"));
            if (!requestedRefs.contains(ref) || normalized.containsKey(ref)) {
                continue;
            }
            List<String> keywords = sanitize(readStringValues(item.get("keywords")), effectiveMaxKeywords(),
                    options().getMaxKeywordChars());
            List<String> questions = sanitize(readStringValues(item.get("questions")), effectiveMaxQuestions(),
                    options().getMaxQuestionChars());
            if (keywords.isEmpty() && questions.isEmpty()) {
                continue;
            }
            normalized.put(ref, new ChunkKeywordQuestion(keywords, questions));
        }
        return normalized;
    }

    /**
     * 读取模型返回的稳定切块引用。只接受字符串，或只包含一个字符串的数组；
     * 多值数组和其他 JSON 类型无法安全映射到唯一切块，必须丢弃。
     */
    private String readChunkRef(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String ref = node.textValue().trim();
            return ref.isEmpty() ? null : ref;
        }
        if (node.isArray() && node.size() == 1 && node.get(0).isTextual()) {
            String ref = node.get(0).textValue().trim();
            return ref.isEmpty() ? null : ref;
        }
        return null;
    }

    /**
     * 兼容模型把字符串数组额外包一层数组的输出，同时限制展开深度，避免把任意结构
     * 猜测成增强文本。对象、数字、布尔值、null 和更深层数组均被忽略。
     */
    private List<String> readStringValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isTextual()) {
            values.add(node.textValue());
            return values;
        }
        if (!node.isArray()) {
            return values;
        }
        for (JsonNode value : node) {
            if (value.isTextual()) {
                values.add(value.textValue());
                continue;
            }
            if (!value.isArray()) {
                continue;
            }
            for (JsonNode nestedValue : value) {
                if (nestedValue.isTextual()) {
                    values.add(nestedValue.textValue());
                }
            }
        }
        return values;
    }

    /**
     * Java 校验（红线 3）：去空、trim、按长度上限裁剪、大小写不敏感去重、数量封顶、剔除注入式内容。
     */
    private List<String> sanitize(List<String> values, int maxCount, int maxChars) {
        List<String> sanitized = new ArrayList<>();
        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        if (values == null) {
            return sanitized;
        }
        for (String value : values) {
            if (sanitized.size() >= maxCount) {
                break;
            }
            String normalized = StrUtil.blankToDefault(value, "").trim();
            if (normalized.isEmpty() || looksLikeInjection(normalized)) {
                continue;
            }
            // 硬截断（不追加省略号）：keywords/questions 作为 BM25 匹配词，需保持词面干净。
            if (normalized.length() > maxChars) {
                normalized = normalized.substring(0, maxChars).trim();
            }
            if (normalized.isEmpty()) {
                continue;
            }
            String dedupeKey = normalized.toLowerCase(Locale.ROOT);
            if (seenKeys.add(dedupeKey)) {
                sanitized.add(normalized);
            }
        }
        return sanitized;
    }

    /**
     * 粗过滤越权/注入式内容：advisor 只应产出源自原文的检索词/问句，不应回显对模型的指令。
     * 注意：这是对 LLM 受控输出的构建期安全校验（红线 3 的"不得含注入指令"），
     * 不是对用户问题的运行时词面硬判，也不含任何业务词/单题特化。
     */
    private boolean looksLikeInjection(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String marker : INJECTION_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private List<List<ChunkEnrichmentItem>> buildBatches(List<ChunkEnrichmentItem> items) {
        List<List<ChunkEnrichmentItem>> batches = new ArrayList<>();
        List<ChunkEnrichmentItem> current = new ArrayList<>();
        int currentTokens = 0;
        for (ChunkEnrichmentItem item : items) {
            int itemTokens = estimateTokens(item);
            if (!current.isEmpty() && (current.size() >= effectiveBatchChunkLimit()
                    || currentTokens + itemTokens > effectiveBatchTokenLimit())) {
                batches.add(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(item);
            currentTokens += itemTokens;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private String renderChunks(List<ChunkEnrichmentItem> batch) throws JsonProcessingException {
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (ChunkEnrichmentItem item : batch) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("chunkRef", item.ref());
            node.put("title", StrUtil.blankToDefault(item.title(), ""));
            node.put("sectionPath", StrUtil.blankToDefault(item.sectionPath(), ""));
            node.put("chunkType", StrUtil.blankToDefault(item.chunkType(), ""));
            node.put("text", StrUtil.maxLength(StrUtil.blankToDefault(item.text(), ""), CHUNK_TEXT_CHAR_LIMIT));
            rendered.add(node);
        }
        return objectMapper.writeValueAsString(rendered);
    }

    private int estimateTokens(ChunkEnrichmentItem item) {
        String text = String.join("\n", StrUtil.blankToDefault(item.title(), ""),
                StrUtil.blankToDefault(item.sectionPath(), ""), StrUtil.blankToDefault(item.chunkType(), ""),
                StrUtil.maxLength(StrUtil.blankToDefault(item.text(), ""), CHUNK_TEXT_CHAR_LIMIT));
        return Math.max(1, (int) Math.ceil(text.trim().length() / 4.0D)) + CHUNK_METADATA_TOKEN_OVERHEAD;
    }

    private ChatCallOptions buildCallOptions() {
        return ChatCallOptions.builder().temperature(0.0D).topP(0.1D).thinking(false).build();
    }

    private String extractJsonObject(String raw) {
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(raw.trim());
        if (matcher.find()) {
            return matcher.group();
        }
        return raw.trim();
    }

    private int effectiveMaxKeywords() {
        return Math.max(1, options().getMaxKeywords());
    }

    private int effectiveMaxQuestions() {
        return Math.max(1, options().getMaxQuestions());
    }

    private int effectiveBatchChunkLimit() {
        return Math.max(1, options().getBatchChunkLimit());
    }

    private int effectiveBatchTokenLimit() {
        return Math.max(512, options().getBatchTokenLimit());
    }

    private SystemConfigSnapshot.ChunkEnrichmentOptions options() {
        return systemConfigProvider == null ? SystemConfigSnapshot.defaults().getChunkEnrichment()
                : systemConfigProvider.currentSnapshot().getChunkEnrichment();
    }

    /**
     * 待增强切块入参。ref 是调用方分配的稳定引用，用于把 LLM 结果回填到对应 ChunkCandidate。
     */
    public record ChunkEnrichmentItem(String ref, String text, String title, String sectionPath, String chunkType) {
    }

    /**
     * 单个切块的受控增强结果（已通过 Java 校验）。
     */
    public record ChunkKeywordQuestion(List<String> keywords, List<String> questions) {
    }

}
