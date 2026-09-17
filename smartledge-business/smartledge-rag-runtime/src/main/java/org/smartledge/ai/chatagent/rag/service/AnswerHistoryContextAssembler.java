package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.chatagent.rag.model.AnswerHistoryContext;
import org.smartledge.ai.chatagent.rag.model.EvidenceAnchor;
import org.smartledge.ai.chatagent.rag.model.QueryType;
import org.smartledge.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @description: 服务层
 * @author: Song
 **/

@Service
public class AnswerHistoryContextAssembler {

    private final ChatRagProperties properties;

    public AnswerHistoryContextAssembler(ChatRagProperties properties) {
        this.properties = properties;
    }

    public AnswerHistoryContext assemble(String question, String answerRecentTranscript) {
        return assemble(question, answerRecentTranscript, null);
    }

    public AnswerHistoryContext assemble(String question,
                                         String answerRecentTranscript,
                                         QueryUnderstandingResult queryUnderstanding) {
        return assemble(question, answerRecentTranscript, queryUnderstanding, List.of());
    }

    public AnswerHistoryContext assemble(String question,
                                         String answerRecentTranscript,
                                         QueryUnderstandingResult queryUnderstanding,
                                         List<EvidenceAnchor> recentEvidenceAnchors) {
        String normalizedQuestion = safeText(question);
        String recentUserContext = extractRecentUserQuestions(answerRecentTranscript);
        int totalBudget = Math.max(1, properties.getAnswerHistoryMaxChars());
        boolean hasRecentContext = StrUtil.isNotBlank(recentUserContext);
        boolean followUpQuestion = looksLikeFollowUpQuestion(normalizedQuestion, queryUnderstanding);
        List<EvidenceAnchor> anchors = safeAnchors(recentEvidenceAnchors);

        if (!followUpQuestion || (!hasRecentContext && anchors.isEmpty())) {
            return emptyContext(totalBudget, followUpQuestion);
        }

        String recentPart = renderRecentContext(recentUserContext, totalBudget);
        String structuredPart = renderStructuredContext(anchors, totalBudget - recentPart.length());
        String renderedText = joinNonBlank(structuredPart, recentPart);
        if (renderedText.isBlank() && anchors.isEmpty()) {
            return emptyContext(totalBudget, followUpQuestion);
        }
        return AnswerHistoryContext.builder()
            .renderedText(renderedText)
            .structuredContext(structuredPart)
            .recentContext(recentPart)
            .evidenceAnchors(anchors)
            .resolvedTopic(resolveTopic(anchors))
            .followUpQuestion(followUpQuestion)
            .totalBudget(totalBudget)
            .recentBudget(recentPart.length())
            .structuredBudget(structuredPart.length())
            .build();
    }

    private AnswerHistoryContext emptyContext(int totalBudget, boolean followUpQuestion) {
        return AnswerHistoryContext.builder()
            .renderedText("")
            .structuredContext("")
            .recentContext("")
            .evidenceAnchors(List.of())
            .resolvedTopic("")
            .followUpQuestion(followUpQuestion)
            .totalBudget(totalBudget)
            .recentBudget(0)
            .structuredBudget(0)
            .build();
    }

    private String extractRecentUserQuestions(String answerRecentTranscript) {
        String normalized = safeText(answerRecentTranscript);
        if (normalized.startsWith("【最近相关对话】")) {
            normalized = normalized.substring("【最近相关对话】".length()).trim();
        }
        if (normalized.startsWith("最近相关对话：")) {
            normalized = normalized.substring("最近相关对话：".length()).trim();
        }
        StringBuilder builder = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String trimmed = safeText(line);
            if (!trimmed.startsWith("用户：")) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(trimmed);
        }
        return builder.toString().trim();
    }

    private boolean looksLikeFollowUpQuestion(String normalizedQuestion,
                                              QueryUnderstandingResult queryUnderstanding) {
        if (StrUtil.isBlank(normalizedQuestion)) {
            return false;
        }
        QueryType queryType = queryUnderstanding == null || queryUnderstanding.getQueryType() == null
            ? QueryType.DOCUMENT_QA
            : queryUnderstanding.getQueryType();
        if (queryType == QueryType.FOLLOW_UP) {
            return true;
        }
        return false;
    }

    private List<EvidenceAnchor> safeAnchors(List<EvidenceAnchor> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        return anchors.stream()
            .filter(anchor -> anchor != null && hasAnchorIdentity(anchor))
            .limit(5)
            .toList();
    }

    private boolean hasAnchorIdentity(EvidenceAnchor anchor) {
        return anchor.getDocumentId() != null
            || anchor.getStructureNodeId() != null
            || anchor.getParentBlockId() != null
            || anchor.getChunkId() != null
            || StrUtil.isNotBlank(anchor.getSectionPath());
    }

    private String renderStructuredContext(List<EvidenceAnchor> anchors, int budget) {
        if (anchors == null || anchors.isEmpty() || budget <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder("上一轮可继承证据锚点（仅用于解析指代和限定范围，不作为事实证据）：\n");
        for (EvidenceAnchor anchor : anchors) {
            if (anchor == null) {
                continue;
            }
            builder.append("- 文档: ").append(blankToDash(anchor.getDocumentName())).append('\n');
            appendAnchorField(builder, "  章节", anchor.getSectionPath());
            appendAnchorField(builder, "  canonicalPath", anchor.getCanonicalPath());
            appendAnchorField(builder, "  structureNodeId", anchor.getStructureNodeId());
            appendAnchorField(builder, "  parentBlockId", anchor.getParentBlockId());
            appendAnchorField(builder, "  chunkId", anchor.getChunkId());
            appendAnchorField(builder, "  itemIndex", anchor.getItemIndex());
            String snippet = clipHead(anchor.getSnippet(), 300);
            appendAnchorField(builder, "  snippet", snippet);
        }
        return clipHead(builder.toString().trim(), budget);
    }

    private void appendAnchorField(StringBuilder builder, String name, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            return;
        }
        builder.append(name).append(": ").append(text).append('\n');
    }

    private String resolveTopic(List<EvidenceAnchor> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return "";
        }
        EvidenceAnchor anchor = anchors.get(0);
        return StrUtil.blankToDefault(anchor.getSectionPath(), StrUtil.blankToDefault(anchor.getDocumentName(), ""));
    }

    private String renderRecentContext(String recentUserContext, int budget) {
        if (budget <= 0 || StrUtil.isBlank(recentUserContext)) {
            return "";
        }
        String title = "对话承接上下文（仅用于理解指代，不作为事实证据）：\n";
        if (budget <= title.length()) {
            return clipTail(recentUserContext, budget);
        }
        String body = clipTail(recentUserContext, budget - title.length());
        if (body.isBlank()) {
            return "";
        }
        return title + body;
    }

    private String joinNonBlank(String first, String second) {
        String left = safeText(first);
        String right = safeText(second);
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank()) {
            return left;
        }
        return left + "\n" + right;
    }

    private String clipHead(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }

    private String clipTail(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        int start = Math.max(0, normalized.length() - (maxChars - 1));
        return "…" + normalized.substring(start);
    }

    private String blankToDash(String text) {
        return StrUtil.blankToDefault(text, "-");
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
