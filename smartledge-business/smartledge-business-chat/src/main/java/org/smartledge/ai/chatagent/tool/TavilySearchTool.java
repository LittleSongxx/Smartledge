package org.smartledge.ai.chatagent.tool;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.config.TavilySearchProperties;
import org.smartledge.ai.chatagent.model.debug.ChatToolTrace;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.support.RestClientFactorySupport;
import org.smartledge.ai.chatagent.support.StreamEventWriter;
import org.smartledge.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.smartledge.ai.chatagent.support.ToolQuerySanitizer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.chatagent.agent.AgentTool;
import org.smartledge.ai.chatagent.agent.AgentToolContext;
import org.smartledge.ai.rag.runtime.model.ChatToolDefinition;
import org.smartledge.ai.chatagent.support.TavilyToolArguments;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * @description: 工具类
 * @author: Song
 **/
@Slf4j
@Component
public class TavilySearchTool implements AgentTool {

    private static final Set<String> ALLOWED_TOPICS = Set.of("general", "news", "finance");

    private final TavilySearchProperties properties;
    private final StreamEventWriter streamEventWriter;
    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final TavilyToolArguments arguments;

    @Override public ChatToolDefinition definition() {
        return new ChatToolDefinition("tavily_search", "联网搜索最新信息、事实资料和网页来源。调用时必须传 JSON 参数，且至少包含非空 query；可选 topic 和 maxResults，其中 topic 仅允许 general、news、finance。",
            Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"),
                "topic", Map.of("type", "string"), "maxResults", Map.of("type", "integer")), "required", List.of("query")));
    }
    @Override public java.time.Duration timeout() {
        return java.time.Duration.ofMillis(3L * (properties.getConnectTimeoutMs() + (long) properties.getReadTimeoutMs()) + 2400);
    }
    @Override public String execute(String rawArguments, AgentToolContext context) throws Exception {
        context.checkActive();
        String normalized = arguments.normalizeArguments(rawArguments, context.question());
        if (normalized == null) throw new IllegalArgumentException("query 不能为空");
        TavilySearchRequest request;
        try { request = mapper.readValue(normalized, TavilySearchRequest.class); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid Tavily arguments", e); }
        return mapper.writeValueAsString(search(request, context));
    }

    public TavilySearchTool(TavilySearchProperties properties, StreamEventWriter streamEventWriter, ObjectMapper mapper, TavilyToolArguments arguments) {
        this.properties = properties;
        this.mapper = mapper;
        this.arguments = arguments;
        if (properties.getConnectTimeoutMs() <= 0 || properties.getReadTimeoutMs() <= 0)
            throw new IllegalArgumentException("Tavily timeouts must be finite");
        this.streamEventWriter = streamEventWriter;
        this.restClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getReadTimeoutMs()
        );
    }

    public TavilySearchToolResult search(TavilySearchRequest request, AgentToolContext toolContext) {

        toolContext.checkActive();
        String rawQuery = request != null && StrUtil.isNotBlank(request.getQuery())
            ? ToolQuerySanitizer.sanitize(request.getQuery())
            : "";
        if (StrUtil.isBlank(rawQuery)) {
            throw new IllegalArgumentException("query 不能为空");
        }
        boolean suspiciousQuery = ToolQuerySanitizer.looksInjected(rawQuery);
        if (suspiciousQuery) {
            // 不阻断：语义仍按普通搜索词处理，但把可疑话术暴露到观测，便于回溯文档诱导。
            log.warn("Tavily 检索词包含疑似提示注入话术，已按普通搜索词处理: {}", rawQuery);
        }
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Tavily 搜索工具当前已禁用");
        }
        if (StrUtil.isBlank(properties.getApiKey())) {
            throw new IllegalStateException("Tavily API Key 未配置");
        }

        long startTime = System.currentTimeMillis();
        String topic = resolveTopic(request);
        ChatToolTrace toolTrace = registerToolTrace(toolContext, ChatToolTrace.builder()
            .toolName("tavily_search")
            .status("RUNNING")
            .inputSummary(suspiciousQuery ? rawQuery + "（含疑似提示注入话术，已按普通搜索词处理）" : rawQuery)
            .topic(topic)
            .build());
        markToolUsed(toolContext, "tavily_search");
        publishThinking(toolContext, "🔍 正在联网搜索: " + rawQuery);

        try {

            String effectiveQuery = buildEffectiveQuery(rawQuery, toolContext);
            if (toolTrace != null) {
                toolContext.updateTrace(toolTrace, trace -> trace.setEffectiveInput(effectiveQuery));
            }

            TavilySearchApiResponse response = restClient.post()
                .uri(properties.getSearchPath())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .body(new TavilySearchApiRequest(
                    effectiveQuery,
                    topic,
                    properties.getSearchDepth(),
                    request != null && request.getMaxResults() != null && request.getMaxResults() > 0
                        ? request.getMaxResults()
                        : properties.getMaxResults(),
                    properties.isIncludeAnswer(),
                    properties.isIncludeRawContent()
                ))
                .retrieve()
                .body(TavilySearchApiResponse.class);

            toolContext.checkActive();
            if (response == null) {
                throw new IllegalStateException("Tavily 返回空响应");
            }

            List<SearchReference> references = new ArrayList<>();
            if (response.results() != null) {
                for (TavilyResultItem item : response.results()) {
                    if (StrUtil.isBlank(item.url())) {
                        continue;
                    }
                    references.add(new SearchReference(
                        item.title(),
                        item.url(),
                        StrUtil.isNotBlank(item.content()) ? item.content() : ""
                    ));
                }
            }

            appendReferences(toolContext, references);
            publishThinking(toolContext, "📚 搜索完成，找到 " + references.size() + " 条候选来源");
            toolContext.updateTrace(toolTrace, trace -> completeToolTrace(trace, response, references.size(), startTime));

            return new TavilySearchToolResult(
                effectiveQuery,
                StrUtil.isNotBlank(response.answer()) ? response.answer() : "",
                List.copyOf(references)
            );
        }
        catch (RuntimeException exception) {

            toolContext.checkActive();
            toolContext.updateTrace(toolTrace, trace -> failToolTrace(trace, exception, startTime));
            publishThinking(toolContext, "⚠️ 搜索失败: " + exception.getMessage());
            log.warn("Tavily 搜索失败, query={}", rawQuery, exception);
            throw exception;
        }
    }

    private String buildEffectiveQuery(String query, AgentToolContext toolContext) {
        if (StrUtil.isBlank(query)) {
            return query;
        }

        return TimeSensitiveQueryHelper.buildEffectiveSearchQuery(query, resolveCurrentDate(toolContext));
    }

    private String resolveCurrentDate(AgentToolContext toolContext) {
        return toolContext.currentDate();
    }

    private String resolveTopic(TavilySearchRequest request) {

        String requestedTopic = normalizeTopic(request != null ? request.getTopic() : null);
        if (requestedTopic != null) {
            return requestedTopic;
        }

        String configuredTopic = normalizeTopic(properties.getTopic());
        if (configuredTopic != null) {
            return configuredTopic;
        }

        if (StrUtil.isNotBlank(properties.getTopic())) {
            log.warn("Tavily 默认 topic 配置不合法: {}, 自动回退为 general", properties.getTopic());
        }
        return "general";
    }

    private String normalizeTopic(String rawTopic) {
        if (StrUtil.isBlank(rawTopic)) {
            return null;
        }

        String normalized = rawTopic.trim().toLowerCase(Locale.ROOT);
        if (ALLOWED_TOPICS.contains(normalized)) {
            return normalized;
        }

        log.warn("收到不受支持的 Tavily topic: {}, 允许值仅为 {}", rawTopic, ALLOWED_TOPICS);
        return null;
    }

    private void appendReferences(AgentToolContext context, List<SearchReference> references) {
        context.addReferences(references);
    }
    private void markToolUsed(AgentToolContext context, String toolName) {
        context.markToolUsed(toolName);
    }
    private void publishThinking(AgentToolContext context, String content) {
        context.thinking(content, streamEventWriter);
    }
    private ChatToolTrace registerToolTrace(AgentToolContext context, ChatToolTrace trace) {
        context.registerTrace(trace);
        return trace;
    }

    private void completeToolTrace(ChatToolTrace toolTrace,
                                   TavilySearchApiResponse response,
                                   int referenceCount,
                                   long startTime) {
        if (toolTrace == null) {
            return;
        }
        toolTrace.setStatus("COMPLETED");
        toolTrace.setReferenceCount(referenceCount);
        toolTrace.setDurationMs(Math.max(0L, System.currentTimeMillis() - startTime));
        String answer = response == null ? "" : StrUtil.blankToDefault(response.answer(), "");
        if (StrUtil.isNotBlank(answer)) {
            toolTrace.setOutputSummary("联网结果已返回，答案摘要：" + clipText(answer, 160));
            return;
        }
        toolTrace.setOutputSummary("联网结果已返回，候选来源 " + referenceCount + " 条");
    }

    private void failToolTrace(ChatToolTrace toolTrace, RuntimeException exception, long startTime) {
        if (toolTrace == null) {
            return;
        }
        toolTrace.setStatus("FAILED");
        toolTrace.setDurationMs(Math.max(0L, System.currentTimeMillis() - startTime));
        toolTrace.setErrorMessage(exception == null ? "" : StrUtil.blankToDefault(exception.getMessage(), ""));
    }

    private String clipText(String value, int maxLength) {
        if (StrUtil.isBlank(value) || maxLength <= 0) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private record TavilySearchApiRequest(
        String query,
        String topic,
        @JsonProperty("search_depth")
        String searchDepth,
        @JsonProperty("max_results")
        int maxResults,
        @JsonProperty("include_answer")
        boolean includeAnswer,
        @JsonProperty("include_raw_content")
        boolean includeRawContent
    ) {
    }

    private record TavilySearchApiResponse(
        String answer,
        List<TavilyResultItem> results
    ) {
    }

    private record TavilyResultItem(
        String title,
        String url,
        String content
    ) {
    }
}
