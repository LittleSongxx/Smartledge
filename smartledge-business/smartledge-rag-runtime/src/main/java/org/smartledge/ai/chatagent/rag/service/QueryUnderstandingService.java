package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.rag.model.AnswerShapePlan;
import org.smartledge.ai.chatagent.rag.model.AnswerShapeRequirement;
import org.smartledge.ai.chatagent.rag.model.QueryType;
import org.smartledge.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.smartledge.ai.chatagent.rag.model.RetrievalIntent;
import org.smartledge.ai.chatagent.rag.model.StructureNavigationIntent;
import org.smartledge.ai.chatagent.rag.model.StructureNavigationOperation;
import org.smartledge.ai.chatagent.service.ObservedChatModelService;
import org.smartledge.ai.prompt.PromptTemplateNames;
import org.smartledge.ai.prompt.PromptTemplateService;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一查询理解入口：把散落在路由、表格、GraphRAG、RAPTOR 的主链路关键词硬判
 * 收口成受控建议。该服务不生成 SQL、不决定最终答案，只输出 Java 主链路可校验的计划信号。
 */
@Slf4j
@Service
public class QueryUnderstandingService {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern SECTION_CODE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    private static final Pattern CHINESE_SECTION_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(章|节|小节)");
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("[“\"']([^”\"']{2,40})[”\"']");

    private static final double ADVISOR_CONFIDENCE_THRESHOLD = 0.72D;
    private static final double STRUCTURE_NAVIGATION_CONFIDENCE_THRESHOLD = 0.65D;

    private final ObjectProvider<ObservedChatModelService> observedChatModelServiceProvider;
    private final ObjectProvider<PromptTemplateService> promptTemplateServiceProvider;
    private final ObjectMapper objectMapper;

    public QueryUnderstandingService(ObjectProvider<ObservedChatModelService> observedChatModelServiceProvider,
                                     ObjectProvider<PromptTemplateService> promptTemplateServiceProvider,
                                     ObjectMapper objectMapper) {
        this.observedChatModelServiceProvider = observedChatModelServiceProvider;
        this.promptTemplateServiceProvider = promptTemplateServiceProvider;
        this.objectMapper = objectMapper;
    }

    public QueryUnderstandingResult understand(String originalQuestion,
                                               String rewrittenQuestion,
                                               List<String> subQuestions,
                                               String historySummary,
                                               String answerRecentTranscript) {
        QueryUnderstandingResult fallback = deterministicFallback(originalQuestion, rewrittenQuestion);
        QueryUnderstandingResult advised = adviseWithModel(originalQuestion, rewrittenQuestion, subQuestions, historySummary, answerRecentTranscript, fallback);
        return validate(advised == null ? fallback : advised, fallback);
    }

    private QueryUnderstandingResult adviseWithModel(String originalQuestion,
                                                     String rewrittenQuestion,
                                                     List<String> subQuestions,
                                                     String historySummary,
                                                     String answerRecentTranscript,
                                                     QueryUnderstandingResult fallback) {
        ObservedChatModelService observedChatModelService = observedChatModelServiceProvider == null ? null : observedChatModelServiceProvider.getIfAvailable();
        PromptTemplateService promptTemplateService = promptTemplateServiceProvider == null ? null : promptTemplateServiceProvider.getIfAvailable();
        if (observedChatModelService == null || promptTemplateService == null) {
            return fallback;
        }
        try {
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_QUERY_UNDERSTANDING, Map.of(
                "originalQuestion", StrUtil.blankToDefault(originalQuestion, ""),
                "rewrittenQuestion", StrUtil.blankToDefault(rewrittenQuestion, ""),
                "subQuestions", subQuestions == null ? List.of() : subQuestions,
                "historySummary", StrUtil.blankToDefault(historySummary, ""),
                "answerRecentTranscript", StrUtil.blankToDefault(answerRecentTranscript, "")
            ));
            String raw = observedChatModelService.callText(
                "document_query_understanding",
                null,
                prompt,
                buildCallOptions(),
                null
            );
            if (StrUtil.isBlank(raw)) {
                return fallback;
            }
            return parseAdvice(raw);
        }
        catch (Exception exception) {
            log.warn("查询理解 advisor 失败，回退确定性结构信号: question='{}', message={}",
                StrUtil.blankToDefault(originalQuestion, ""),
                exception.getMessage());
            return fallback;
        }
    }

    private QueryUnderstandingResult parseAdvice(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));
        QueryUnderstandingResult result = QueryUnderstandingResult.builder()
            .queryType(parseQueryType(root.path("queryType").asText("")))
            .channels(parseChannels(root.path("channels")))
            .entities(readStringArray(root.path("entities"), 8))
            .targetEntities(readStringArray(root.path("targetEntities"), 8))
            .excludedEntities(readStringArray(root.path("excludedEntities"), 8))
            .sectionAnchors(readStringArray(root.path("sectionAnchors"), 8))
            .structureNavigationIntent(parseStructureNavigationIntent(root.path("structureNavigationIntent")))
            .tableOps(readStringArray(root.path("tableOps"), 8))
            .answerShapePlan(parseAnswerShapePlan(root.path("answerShape")))
            .confidence(normalizeConfidence(root.path("confidence").asDouble(0D)))
            .reasons(readStringArray(root.path("reasons"), 8))
            .source("llm-query-understanding")
            .build();
        if (result.getReasons().isEmpty()) {
            result.setReasons(List.of("LLM 查询理解完成。"));
        }
        return result;
    }

    private QueryUnderstandingResult validate(QueryUnderstandingResult advised, QueryUnderstandingResult fallback) {
        if (advised == null) {
            return fallback;
        }
        double confidence = normalizeConfidence(advised.getConfidence());
        QueryType queryType = advised.getQueryType() == null ? QueryType.DOCUMENT_QA : advised.getQueryType();
        LinkedHashSet<RetrievalIntent> channels = new LinkedHashSet<>();
        if (fallback != null && fallback.getChannels() != null) {
            channels.addAll(fallback.getChannels());
        }
        boolean highConfidence = confidence >= ADVISOR_CONFIDENCE_THRESHOLD;
        if (highConfidence && advised.getChannels() != null) {
            channels.addAll(advised.getChannels());
        }
        if (channels.isEmpty()) {
            channels.add(RetrievalIntent.GENERAL);
        }
        QueryType effectiveType = highConfidence ? queryType : fallback == null ? QueryType.DOCUMENT_QA : fallback.getQueryType();
        double effectiveConfidence = highConfidence
            ? confidence
            : fallback == null ? confidence : normalizeConfidence(fallback.getConfidence());
        boolean deterministicStructureNavigation = fallback != null
            && fallback.getQueryType() == QueryType.STRUCTURE_NAVIGATION
            && isConfidentStructureNavigationIntent(fallback.getStructureNavigationIntent());
        // 当前轮明确的目录/相邻章节语法是确定性控制信号，advisor 可补充语义通道但不能将其降级。
        if (deterministicStructureNavigation) {
            effectiveType = QueryType.STRUCTURE_NAVIGATION;
            effectiveConfidence = normalizeConfidence(fallback.getConfidence());
        }
        List<String> deterministicAnchors = limitStrings(fallback == null ? null : fallback.getSectionAnchors(), 8);
        StructureNavigationIntent structureNavigationIntent = selectStructureNavigationIntent(
            effectiveType,
            effectiveConfidence,
            advised,
            fallback,
            deterministicAnchors
        );
        List<String> sectionAnchors = structureNavigationIntent == null
            ? deterministicAnchors
            : mergeStrings(deterministicAnchors, structureNavigationIntent.getSectionAnchors(), 8);
        List<String> reasons = new ArrayList<>();
        if (advised.getReasons() != null) {
            reasons.addAll(advised.getReasons());
        }
        if (deterministicStructureNavigation && queryType != QueryType.STRUCTURE_NAVIGATION) {
            reasons.add("当前轮确定性结构语法已确认，保留 STRUCTURE_NAVIGATION；advisor 只补充语义通道。");
        }
        if (!highConfidence) {
            reasons.add("advisor 置信度不足，保留确定性保守计划。");
        }
        if (effectiveType == QueryType.STRUCTURE_NAVIGATION && structureNavigationIntent == null) {
            QueryType fallbackType = fallback == null || fallback.getQueryType() == null
                ? QueryType.DOCUMENT_QA
                : fallback.getQueryType();
            effectiveType = fallbackType == QueryType.STRUCTURE_NAVIGATION ? QueryType.DOCUMENT_QA : fallbackType;
            effectiveConfidence = fallback == null ? 0D : normalizeConfidence(fallback.getConfidence());
            channels.remove(RetrievalIntent.STRUCTURE);
            if (channels.isEmpty()) {
                channels.add(RetrievalIntent.GENERAL);
            }
            reasons.add("structure navigation 缺少合法 intent，按当前轮确定性结果 fail closed。");
        }
        QueryUnderstandingResult.QueryUnderstandingResultBuilder builder = QueryUnderstandingResult.builder()
            .queryType(effectiveType == null ? QueryType.DOCUMENT_QA : effectiveType)
            .channels(new ArrayList<>(channels))
            .entities(limitStrings(advised.getEntities(), 8))
            .targetEntities(limitStrings(advised.getTargetEntities(), 8))
            .excludedEntities(limitStrings(advised.getExcludedEntities(), 8))
            .sectionAnchors(sectionAnchors)
            .structureNavigationIntent(structureNavigationIntent)
            .tableOps(limitStrings(advised.getTableOps(), 8))
            .answerShapePlan(highConfidence
                ? AnswerShapePlan.of(advised.getAnswerShapePlan() == null
                    ? List.of()
                    : advised.getAnswerShapePlan().requirements())
                : AnswerShapePlan.empty())
            .confidence(effectiveConfidence)
            .reasons(limitStrings(reasons, 10))
            .source(StrUtil.blankToDefault(advised.getSource(), "query-understanding"));
        return builder.build();
    }

    private QueryUnderstandingResult deterministicFallback(String originalQuestion, String rewrittenQuestion) {
        String normalized = firstNonBlank(originalQuestion, rewrittenQuestion);
        boolean strictStructureNavigation = looksStrictStructureNavigation(normalized);
        boolean outline = looksOutlineNavigation(normalized);
        boolean structureNavigation = strictStructureNavigation || outline;
        // 章节号和中文章节引用本身是稳定结构语法；引号文本只有配合完整结构导航语法时才是结构锚点。
        List<String> anchors = extractSectionAnchors(normalized, strictStructureNavigation);
        List<StructureNavigationOperation> structureOperations = determineStructureOperations(normalized, strictStructureNavigation, outline);
        // 确定性 fallback 只识别结构导航语法（章节号 / 引号锚点 / 上一节 / 下一节 / 父章节 / 直接子章节）。
        // 不识别表格、GraphRAG、RAPTOR、证据角色等语义——这些只能由受控 LLM advisor 输出；
        // fallback 未命中结构语法时一律按普通文档问答处理，交多通道召回和证据判断解决。
        QueryType queryType = structureNavigation ? QueryType.STRUCTURE_NAVIGATION : QueryType.DOCUMENT_QA;
        LinkedHashSet<RetrievalIntent> channels = new LinkedHashSet<>();
        channels.add(RetrievalIntent.GENERAL);
        if (structureNavigation || !anchors.isEmpty()) {
            channels.add(RetrievalIntent.STRUCTURE);
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("确定性 fallback 仅识别明确结构导航语法，其余按普通文档问答交多通道检索处理。");
        return QueryUnderstandingResult.builder()
            .queryType(queryType)
            .channels(new ArrayList<>(channels))
            .sectionAnchors(anchors)
            .structureNavigationIntent(structureOperations.isEmpty()
                ? null
                : StructureNavigationIntent.builder()
                    .operations(structureOperations)
                    .sectionAnchors(anchors)
                    .confidence(structureNavigation ? 0.86D : 0.55D)
                    .source("java-deterministic-fallback")
                    .build())
            .answerShapePlan(AnswerShapePlan.empty())
            .confidence(structureNavigation ? 0.86D : 0.55D)
            .reasons(reasons)
            .source("java-deterministic-fallback")
            .build();
    }

    private boolean looksStrictStructureNavigation(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return false;
        }
        boolean asksAdjacentSection = containsAny(normalized, List.of("上一节", "下一节", "前一节", "后一节", "上一章", "下一章"));
        boolean asksSectionLocation = containsAny(normalized, List.of("属于哪个章节", "哪个章节", "哪个小节", "哪一节", "哪一章", "章节位置"));
        boolean hasExplicitAnchor = SECTION_CODE_PATTERN.matcher(normalized).find()
            || CHINESE_SECTION_REFERENCE_PATTERN.matcher(normalized).find()
            || QUOTED_TEXT_PATTERN.matcher(normalized).find();
        return asksAdjacentSection || (asksSectionLocation && hasExplicitAnchor);
    }

    private boolean looksOutlineNavigation(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized, List.of("包含哪些章节", "都包含哪些章节", "有哪些章节", "有哪些小节", "包含哪些小节", "章节列表", "展开目录"));
    }

    private List<StructureNavigationOperation> determineStructureOperations(String question,
                                                                           boolean strictStructureNavigation,
                                                                           boolean outline) {
        if (outline) {
            return List.of(StructureNavigationOperation.SECTION_WITH_CHILDREN);
        }
        if (!strictStructureNavigation) {
            return List.of();
        }
        String normalized = safeText(question);
        if (containsAny(normalized, List.of("上一节", "下一节", "前一节", "后一节", "上一章", "下一章", "相邻章节", "同一一级章节"))) {
            return List.of(StructureNavigationOperation.SECTION_WITH_SIBLINGS);
        }
        if (containsAny(normalized, List.of("属于哪个章节", "哪个章节", "哪个小节", "哪一节", "哪一章", "章节位置"))) {
            return List.of(StructureNavigationOperation.PARENT_SECTION);
        }
        return List.of(StructureNavigationOperation.CURRENT_SECTION);
    }

    private List<String> extractSectionAnchors(String text, boolean includeQuotedText) {
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        Matcher sectionMatcher = SECTION_CODE_PATTERN.matcher(safeText(text));
        while (sectionMatcher.find()) {
            anchors.add(sectionMatcher.group(1));
        }
        Matcher chineseMatcher = CHINESE_SECTION_REFERENCE_PATTERN.matcher(safeText(text));
        while (chineseMatcher.find()) {
            anchors.add(chineseMatcher.group());
        }
        if (includeQuotedText) {
            Matcher quotedMatcher = QUOTED_TEXT_PATTERN.matcher(safeText(text));
            while (quotedMatcher.find()) {
                String phrase = quotedMatcher.group(1);
                if (StrUtil.isNotBlank(phrase)) {
                    anchors.add(phrase.trim());
                }
            }
        }
        return anchors.stream().limit(8).toList();
    }

    private StructureNavigationIntent parseStructureNavigationIntent(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<StructureNavigationOperation> operations = parseStructureOperations(node.path("operations"));
        return StructureNavigationIntent.builder()
            .operations(operations)
            .anchorStructureNodeId(node.path("anchorStructureNodeId").isNumber() ? node.path("anchorStructureNodeId").asLong() : null)
            .anchorSectionPath(node.path("anchorSectionPath").asText(""))
            .anchorCanonicalPath(node.path("anchorCanonicalPath").asText(""))
            .sectionAnchors(readStringArray(node.path("sectionAnchors"), 8))
            .confidence(normalizeConfidence(node.path("confidence").asDouble(0D)))
            .source("llm-query-understanding")
            .build();
    }

    private List<StructureNavigationOperation> parseStructureOperations(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<StructureNavigationOperation> operations = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String normalized = item.asText("").trim().toUpperCase(Locale.ROOT);
            try {
                operations.add(StructureNavigationOperation.valueOf(normalized));
            }
            catch (IllegalArgumentException ignored) {
                // 丢弃未知结构导航操作，Java 主链路只接受白名单枚举。
            }
        }
        return new ArrayList<>(operations);
    }

    private AnswerShapePlan parseAnswerShapePlan(JsonNode node) {
        if (node == null || !node.isArray()) {
            return AnswerShapePlan.empty();
        }
        LinkedHashSet<AnswerShapeRequirement> requirements = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText("").isBlank()) {
                return AnswerShapePlan.empty();
            }
            try {
                requirements.add(AnswerShapeRequirement.valueOf(item.asText("").trim().toUpperCase(Locale.ROOT)));
            }
            catch (IllegalArgumentException exception) {
                return AnswerShapePlan.empty();
            }
        }
        return AnswerShapePlan.of(new ArrayList<>(requirements));
    }

    private StructureNavigationIntent selectStructureNavigationIntent(QueryType effectiveType,
                                                                     double confidence,
                                                                     QueryUnderstandingResult advised,
                                                                     QueryUnderstandingResult fallback,
                                                                     List<String> sectionAnchors) {
        if (effectiveType != QueryType.STRUCTURE_NAVIGATION || confidence < STRUCTURE_NAVIGATION_CONFIDENCE_THRESHOLD) {
            return null;
        }
        StructureNavigationIntent fallbackIntent = fallback == null ? null : fallback.getStructureNavigationIntent();
        if (fallback != null && fallback.getQueryType() == QueryType.STRUCTURE_NAVIGATION
            && isConfidentStructureNavigationIntent(fallbackIntent)) {
            return normalizeStructureNavigationIntent(fallbackIntent, sectionAnchors, "java-deterministic-fallback");
        }
        StructureNavigationIntent advisedIntent = advised == null ? null : advised.getStructureNavigationIntent();
        if (isConfidentStructureNavigationIntent(advisedIntent)) {
            return normalizeStructureNavigationIntent(advisedIntent, sectionAnchors, "llm-query-understanding");
        }
        return null;
    }

    private boolean isValidStructureNavigationIntent(StructureNavigationIntent intent) {
        return intent != null && intent.getOperations() != null && !intent.getOperations().isEmpty();
    }

    private boolean isConfidentStructureNavigationIntent(StructureNavigationIntent intent) {
        return isValidStructureNavigationIntent(intent)
            && normalizeConfidence(intent.getConfidence()) >= STRUCTURE_NAVIGATION_CONFIDENCE_THRESHOLD;
    }

    private StructureNavigationIntent normalizeStructureNavigationIntent(StructureNavigationIntent intent,
                                                                        List<String> sectionAnchors,
                                                                        String fallbackSource) {
        List<StructureNavigationOperation> operations = intent.getOperations().stream()
            .filter(operation -> operation != null)
            .distinct()
            .limit(4)
            .toList();
        if (operations.isEmpty()) {
            return null;
        }
        return StructureNavigationIntent.builder()
            .operations(operations)
            .anchorStructureNodeId(intent.getAnchorStructureNodeId())
            .anchorSectionPath(StrUtil.blankToDefault(intent.getAnchorSectionPath(), ""))
            .anchorCanonicalPath(StrUtil.blankToDefault(intent.getAnchorCanonicalPath(), ""))
            .sectionAnchors(mergeStrings(sectionAnchors, intent.getSectionAnchors(), 8))
            .confidence(normalizeConfidence(intent.getConfidence()))
            .source(StrUtil.blankToDefault(intent.getSource(), fallbackSource))
            .build();
    }

    private QueryType parseQueryType(String raw) {
        String normalized = StrUtil.blankToDefault(raw, "").trim().toUpperCase(Locale.ROOT);
        try {
            return normalized.isBlank() ? QueryType.DOCUMENT_QA : QueryType.valueOf(normalized);
        }
        catch (IllegalArgumentException exception) {
            return QueryType.DOCUMENT_QA;
        }
    }

    private List<RetrievalIntent> parseChannels(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<RetrievalIntent> channels = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String normalized = item.asText("").trim().toUpperCase(Locale.ROOT);
            if ("VECTOR".equals(normalized) || "BM25".equals(normalized) || "KEYWORD".equals(normalized)) {
                channels.add(RetrievalIntent.GENERAL);
                continue;
            }
            try {
                channels.add(RetrievalIntent.valueOf(normalized));
            }
            catch (IllegalArgumentException ignored) {
                // 丢弃未知通道，Java 主链路只接受白名单枚举。
            }
        }
        return new ArrayList<>(channels);
    }

    private List<String> readStringArray(JsonNode node, int limit) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (StrUtil.isNotBlank(value) && !values.contains(value)) {
                values.add(value);
            }
            if (values.size() >= limit) {
                break;
            }
        }
        return values;
    }

    private List<String> mergeStrings(List<String> first, List<String> second, int limit) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (first != null) {
            first.stream().filter(StrUtil::isNotBlank).forEach(values::add);
        }
        if (second != null) {
            second.stream().filter(StrUtil::isNotBlank).forEach(values::add);
        }
        return values.stream().limit(limit).toList();
    }

    private List<String> limitStrings(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(StrUtil::isNotBlank)
            .distinct()
            .limit(limit)
            .toList();
    }

    private boolean containsAny(String text, List<String> terms) {
        String normalized = safeText(text);
        if (normalized.isBlank() || terms == null || terms.isEmpty()) {
            return false;
        }
        return terms.stream().filter(StrUtil::isNotBlank).anyMatch(normalized::contains);
    }

    private String firstNonBlank(String first, String second) {
        return StrUtil.isNotBlank(first) ? first.trim() : safeText(second);
    }

    private ChatCallOptions buildCallOptions() {
        return ChatCallOptions.builder()
            .temperature(0.0D)
            .topP(0.1D)
            .thinking(false)
            .build();
    }

    private String extractJsonObject(String raw) {
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(raw.trim());
        if (matcher.find()) {
            return matcher.group();
        }
        return raw.trim();
    }

    private double normalizeConfidence(double confidence) {
        if (confidence > 1D) {
            return confidence / 100D;
        }
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, confidence));
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
