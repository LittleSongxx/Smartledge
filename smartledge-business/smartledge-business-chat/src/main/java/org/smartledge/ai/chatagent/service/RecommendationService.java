package org.smartledge.ai.chatagent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.rag.runtime.config.ChatAgentProperties;
import org.smartledge.ai.chatagent.model.ConversationExchangeView;
import org.smartledge.ai.prompt.PromptTemplateNames;
import org.smartledge.ai.prompt.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * @description: 服务层
 * @author: Song
 **/
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final ChatAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService recommendationExecutorService;
    private final ObservedChatModelService observedChatModelService;
    private final PromptTemplateService promptTemplateService;

    public RecommendationService(ChatAgentProperties properties,
                                 ObjectMapper objectMapper,
                                 @Qualifier("chatPostProcessExecutorService") ExecutorService recommendationExecutorService,
                                 ObservedChatModelService observedChatModelService,
                                 PromptTemplateService promptTemplateService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.recommendationExecutorService = recommendationExecutorService;
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
    }

    public List<String> generateRecommendations(String question,
                                                String answer,
                                                List<ConversationExchangeView> recentExchanges,
                                                ConversationTraceRecorder traceRecorder) {

        if (!properties.isRecommendationEnabled() || StrUtil.isBlank(answer)) {
            return List.of();
        }

        Future<List<String>> task = recommendationExecutorService.submit(
            () -> generateRecommendationsInternal(question, answer, recentExchanges, traceRecorder));
        try {
            return task.get(Math.max(properties.getRecommendationTimeoutMs(), 1L), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        catch (Exception exception) {
            log.warn("生成推荐问题超时或失败: {}", exception.getClass().getSimpleName());
            return List.of();
        }
        finally {
            if (!task.isDone()) { task.cancel(true); }
        }
    }

    private List<String> generateRecommendationsInternal(String question,
                                                         String answer,
                                                         List<ConversationExchangeView> recentExchanges,
                                                         ConversationTraceRecorder traceRecorder) {

        List<ConversationExchangeView> safeRecentExchanges = recentExchanges == null ? List.of() : recentExchanges;
        StringBuilder recentContext = new StringBuilder();

        int startIndex = Math.max(0, safeRecentExchanges.size() - properties.getHistoryPreviewTurns());

        for (int index = startIndex; index < safeRecentExchanges.size(); index++) {
            ConversationExchangeView exchange = safeRecentExchanges.get(index);
            recentContext.append("用户：").append(exchange.getQuestion()).append('\n');
            if (StrUtil.isNotBlank(exchange.getAnswer())) {
                recentContext.append("助手：").append(exchange.getAnswer()).append('\n');
            }
        }

        String prompt = promptTemplateService.render(PromptTemplateNames.RECOMMENDATION_USER, Map.of(
            "recentContext", recentContext.toString().trim(),
            "question", StrUtil.blankToDefault(question, ""),
            "answer", StrUtil.blankToDefault(answer, "")
        ));

        try {

            String content = observedChatModelService.callText("recommendation", null, prompt, traceRecorder);

            if (StrUtil.isBlank(content)) {
                return List.of();
            }

            String jsonArray = extractJsonArray(content);
            if (StrUtil.isBlank(jsonArray)) {
                log.warn("推荐问题输出不是有效 JSON 数组: {}", content);
                return List.of();
            }

            List<String> rawList = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {
            });
            LinkedHashSet<String> unique = new LinkedHashSet<>();

            for (String item : rawList) {
                if (StrUtil.isNotBlank(item)) {
                    unique.add(item.trim());
                }
                if (unique.size() >= 3) {
                    break;
                }
            }
            return new ArrayList<>(unique);
        }
        catch (Exception exception) {
            log.warn("生成推荐问题失败", exception);
            return List.of();
        }
    }

    private String extractJsonArray(String content) {

        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }
}
