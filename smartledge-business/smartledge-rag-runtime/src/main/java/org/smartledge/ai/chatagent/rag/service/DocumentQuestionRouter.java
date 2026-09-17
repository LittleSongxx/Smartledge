package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.rag.model.ConversationItemAnchor;
import org.smartledge.ai.chatagent.rag.model.ConversationStructureAnchor;
import org.smartledge.ai.chatagent.rag.model.DocumentNavigationAction;
import org.smartledge.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.smartledge.ai.chatagent.rag.model.ExecutionMode;
import org.smartledge.ai.chatagent.rag.model.QueryType;
import org.smartledge.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.smartledge.ai.chatagent.rag.model.RagRewriteResult;
import org.smartledge.ai.chatagent.rag.model.RetrievalIntent;
import org.smartledge.ai.chatagent.rag.model.StructureNavigationIntent;
import org.smartledge.ai.chatagent.rag.model.StructureNavigationOperation;
import org.smartledge.ai.rag.runtime.model.graph.GraphSection;
import org.smartledge.ai.rag.runtime.port.DocumentNavigationIndexPort;
import org.smartledge.ai.rag.runtime.port.DocumentStructureGraphPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description: 文档问答结构导航锚点解析器。
 *
 * <p>本类不承担“图直答/图定位取证”执行分叉，也不使用关键词、短语剥离或正文 contains 打分决定意图。
 * 它只做两件确定性的事：</p>
 * <ol>
 *   <li>把受控 {@link QueryUnderstandingResult} 里的结构导航意图翻译成结构导航动作（结构语法，非业务词）。</li>
 *   <li>用<b>精确锚点</b>（章节号 / 引号标题精确 / 导航索引命中）定位结构节点，供确定性结构查询和软提示使用。</li>
 * </ol>
 *
 * <p>所有文档问答（当前文档 / 自动知识）统一输出 {@link ExecutionMode#RETRIEVAL}，进入统一多通道混合检索；
 * 结构导航结果只作为检索上下文和观测信号，不得绕过混合检索。</p>
 * @author: Song
 **/

@Slf4j
@Service
public class DocumentQuestionRouter {

    // 匹配 1.2 / 3.4.5 这类章节编号，用于识别用户给出的结构锚点。
    private static final Pattern SECTION_CODE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    // 匹配“第 3 章 / 第三节 / 第 4 小节”，补齐非小数编号的章节锚点表达。
    private static final Pattern CHINESE_SECTION_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(章|节|小节)");
    // 匹配“第几步”，用于识别明确编号项结构锚点。
    private static final Pattern STEP_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*步");
    // 匹配“第几条/点/项/个”，用于保留原有编号项定位能力。
    private static final Pattern ORDINAL_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(条|点|项|个)");
    // 匹配用户用引号包住的标题短语，例如“上线观察”，用于结构节点精确定位。
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("[“\"']([^”\"']{2,40})[”\"']");

    private final DocumentStructureGraphPort graphService;
    private final ObjectProvider<DocumentNavigationIndexPort> navigationIndexServiceProvider;
    private final QueryUnderstandingService queryUnderstandingService;

    @Autowired
    public DocumentQuestionRouter(DocumentStructureGraphPort graphService,
                                  ObjectProvider<DocumentNavigationIndexPort> navigationIndexServiceProvider,
                                  ObjectProvider<QueryUnderstandingService> queryUnderstandingServiceProvider) {
        this.graphService = graphService;
        this.navigationIndexServiceProvider = navigationIndexServiceProvider;
        this.queryUnderstandingService = queryUnderstandingServiceProvider == null ? null : queryUnderstandingServiceProvider.getIfAvailable();
    }

    public DocumentNavigationDecision route(Long documentId,
                                            String originalQuestion,
                                            RagRewriteResult rewriteResult,
                                            String historySummary,
                                            String answerRecentTranscript) {
        String rewrittenQuestion = firstNonBlank(
            rewriteResult == null ? "" : rewriteResult.getRewrittenQuestion(),
            originalQuestion
        );
        List<String> subQuestions = normalizeSubQuestions(rewriteResult, rewrittenQuestion);
        String routeText = (safeText(originalQuestion) + " " + rewrittenQuestion).trim();
        QueryUnderstandingResult queryUnderstanding = understandQuery(originalQuestion, rewrittenQuestion, subQuestions, historySummary, answerRecentTranscript);
        RetrievalIntent retrievalIntent = primaryRetrievalIntent(queryUnderstanding);

        // 高置信结构导航：走结构树确定性查询，结果作为检索上下文和观测信号（仍是 RETRIEVAL，进统一混合检索）。
        DocumentNavigationAction structureNavigationAction = resolveStructureNavigationAction(queryUnderstanding);
        if (structureNavigationAction != null) {
            GraphSection section = resolveSection(documentId, originalQuestion, rewrittenQuestion);
            return buildDecision(
                structureNavigationAction,
                section,
                null,
                queryUnderstanding,
                RetrievalIntent.STRUCTURE,
                "高置信结构导航走结构树确定性查询，结构结果作为检索上下文和观测信号。"
            );
        }

        // 明确编号项（第 N 步 / 第 N 项）：作为结构锚点软辅助混合检索，不再单独走图定位取证分叉。
        Integer itemIndex = subQuestions.size() <= 1 ? resolveExplicitItemIndex(routeText) : null;
        boolean hasExplicitStructureAnchor = itemIndex != null
            || hasExplicitSectionAnchor(routeText)
            || hasSectionAnchor(queryUnderstanding);
        GraphSection assistedSection = hasExplicitStructureAnchor
            ? resolveSection(documentId, originalQuestion, rewrittenQuestion)
            : null;
        return buildDecision(
            itemIndex != null ? DocumentNavigationAction.ITEM_REFERENCE : DocumentNavigationAction.FRESH_TOPIC,
            assistedSection,
            itemIndex,
            queryUnderstanding,
            retrievalIntent,
            assistedSection == null
                ? "普通文档问题走混合检索"
                : "结构锚点仅作为软提示辅助混合检索"
        );
    }

    private DocumentNavigationDecision buildDecision(DocumentNavigationAction action,
                                                     GraphSection section,
                                                     Integer itemIndex,
                                                     QueryUnderstandingResult queryUnderstanding,
                                                     RetrievalIntent retrievalIntent,
                                                     String reason) {
        ConversationStructureAnchor structureAnchor = section == null
            ? ConversationStructureAnchor.builder().scopeMode("NONE").build()
            : ConversationStructureAnchor.builder()
                .rootSectionCode(section.getNodeCode())
                .rootSectionTitle(section.getTitle())
                .targetSectionHint(section.displayTitle())
                .structureNodeId(section.getNodeId())
                .canonicalPath(section.getCanonicalPath())
                .scopeMode("SOFT")
                .build();
        ConversationItemAnchor itemAnchor = itemIndex == null
            ? null
            : ConversationItemAnchor.builder().itemIndex(itemIndex).build();
        RetrievalIntent effectiveIntent = retrievalIntent == null ? RetrievalIntent.GENERAL : retrievalIntent;
        String summaryText = "mode=" + ExecutionMode.RETRIEVAL.name()
            + "; retrievalIntent=" + effectiveIntent.name()
            + "; queryType=" + (queryUnderstanding == null || queryUnderstanding.getQueryType() == null ? "" : queryUnderstanding.getQueryType().name())
            + "; queryUnderstandingSource=" + (queryUnderstanding == null ? "" : StrUtil.blankToDefault(queryUnderstanding.getSource(), ""))
            + "; reason=" + reason
            + "; section=" + (section == null ? "" : section.displayTitle())
            + "; itemIndex=" + (itemIndex == null ? "" : itemIndex);
        log.info("文档问答路由完成: mode=RETRIEVAL, action={}, section='{}', itemIndex={}, reason='{}'",
            action,
            section == null ? "" : section.displayTitle(),
            itemIndex,
            reason);
        return DocumentNavigationDecision.builder()
            .navigationAction(action)
            .executionMode(ExecutionMode.RETRIEVAL)
            .structureAnchor(structureAnchor)
            .itemAnchor(itemAnchor)
            .queryUnderstanding(queryUnderstanding)
            .retrievalIntent(effectiveIntent)
            .summaryText(summaryText)
            .queryContextHints(List.of())
            .softSectionHints(List.of())
            .build();
    }

    private QueryUnderstandingResult understandQuery(String originalQuestion,
                                                     String rewrittenQuestion,
                                                     List<String> subQuestions,
                                                     String historySummary,
                                                     String answerRecentTranscript) {
        if (queryUnderstandingService == null) {
            return null;
        }
        return queryUnderstandingService.understand(originalQuestion, rewrittenQuestion, subQuestions, historySummary, answerRecentTranscript);
    }

    private DocumentNavigationAction resolveStructureNavigationAction(QueryUnderstandingResult queryUnderstanding) {
        if (queryUnderstanding == null || queryUnderstanding.getQueryType() != QueryType.STRUCTURE_NAVIGATION) {
            return null;
        }
        if (confidence(queryUnderstanding) < 0.65D) {
            return null;
        }
        StructureNavigationIntent intent = queryUnderstanding.getStructureNavigationIntent();
        if (intent == null || intent.getOperations() == null || intent.getOperations().isEmpty()) {
            return null;
        }
        List<StructureNavigationOperation> operations = intent.getOperations();
        if (operations.contains(StructureNavigationOperation.SECTION_WITH_CHILDREN)
            || operations.contains(StructureNavigationOperation.DIRECT_CHILDREN)) {
            return DocumentNavigationAction.CHILD_SECTION_DESCEND;
        }
        if (operations.contains(StructureNavigationOperation.SECTION_WITH_SIBLINGS)
            || operations.contains(StructureNavigationOperation.PREVIOUS_SIBLING)
            || operations.contains(StructureNavigationOperation.NEXT_SIBLING)) {
            return DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP;
        }
        if (operations.contains(StructureNavigationOperation.PARENT_SECTION)) {
            return DocumentNavigationAction.ANCESTOR_SECTION_RETURN;
        }
        if (operations.contains(StructureNavigationOperation.CURRENT_SECTION)) {
            return DocumentNavigationAction.FRESH_TOPIC;
        }
        return null;
    }

    private RetrievalIntent primaryRetrievalIntent(QueryUnderstandingResult queryUnderstanding) {
        if (queryUnderstanding == null) {
            return RetrievalIntent.GENERAL;
        }
        QueryType queryType = queryUnderstanding.getQueryType() == null ? QueryType.DOCUMENT_QA : queryUnderstanding.getQueryType();
        if (queryType == QueryType.STRUCTURE_NAVIGATION) {
            return RetrievalIntent.STRUCTURE;
        }
        if (queryType == QueryType.TABLE_QUERY) {
            return RetrievalIntent.TABLE;
        }
        if (queryType == QueryType.GRAPH_RELATION) {
            return RetrievalIntent.GRAPH_RAG;
        }
        if (queryType == QueryType.GLOBAL_SUMMARY) {
            return RetrievalIntent.RAPTOR;
        }
        List<RetrievalIntent> channels = queryUnderstanding.getChannels() == null ? List.of() : queryUnderstanding.getChannels();
        if (channels.contains(RetrievalIntent.TABLE)) {
            return RetrievalIntent.TABLE;
        }
        if (channels.contains(RetrievalIntent.GRAPH_RAG)) {
            return RetrievalIntent.GRAPH_RAG;
        }
        if (channels.contains(RetrievalIntent.RAPTOR)) {
            return RetrievalIntent.RAPTOR;
        }
        if (channels.contains(RetrievalIntent.STRUCTURE)) {
            return RetrievalIntent.STRUCTURE;
        }
        return RetrievalIntent.GENERAL;
    }

    /**
     * 结构节点定位只允许精确锚点：章节号精确、引号标题精确、导航索引命中。
     * 不再用正文 contains 打分或短语剥离作为结构锚点权威来源；未命中时返回 null，交混合检索处理。
     */
    private GraphSection resolveSection(Long documentId, String originalQuestion, String rewrittenQuestion) {
        if (documentId == null) {
            return null;
        }
        GraphSection currentTurn = resolveSection(documentId, originalQuestion);
        if (currentTurn != null) {
            return currentTurn;
        }
        if (StrUtil.equals(StrUtil.trim(originalQuestion), StrUtil.trim(rewrittenQuestion))) {
            return null;
        }
        return resolveSection(documentId, rewrittenQuestion);
    }

    private GraphSection resolveSection(Long documentId, String question) {
        GraphSection byCode = resolveBySectionCode(documentId, question);
        if (byCode != null) {
            return byCode;
        }
        GraphSection byQuotedTitle = resolveByQuotedTitle(documentId, question);
        if (byQuotedTitle != null) {
            return byQuotedTitle;
        }
        return resolveByNavigationIndex(documentId, question);
    }

    private GraphSection resolveBySectionCode(Long documentId, String question) {
        Matcher matcher = SECTION_CODE_PATTERN.matcher(safeText(question));
        while (matcher.find()) {
            GraphSection section = graphService.findSectionByCode(documentId, matcher.group(1));
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    private GraphSection resolveByQuotedTitle(Long documentId, String question) {
        LinkedHashSet<String> quotedPhrases = new LinkedHashSet<>();
        collectQuotedPhrases(quotedPhrases, question);
        if (quotedPhrases.isEmpty()) {
            return null;
        }
        List<GraphSection> sections = graphService.listSections(documentId);
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        for (String phrase : quotedPhrases) {
            String normalizedPhrase = normalize(phrase);
            if (normalizedPhrase.length() < 2) {
                continue;
            }
            GraphSection exactTitle = sections.stream()
                .filter(section -> normalize(section.getTitle()).equals(normalizedPhrase)
                    || normalize(section.displayTitle()).equals(normalizedPhrase)
                    || normalize(section.getSectionPath()).endsWith(normalizedPhrase))
                .findFirst()
                .orElse(null);
            if (exactTitle != null) {
                return exactTitle;
            }
        }
        return null;
    }

    private GraphSection resolveByNavigationIndex(Long documentId, String question) {
        DocumentNavigationIndexPort navigationIndexService = navigationIndexServiceProvider.getIfAvailable();
        if (navigationIndexService == null) {
            return null;
        }
        String query = safeText(question);
        List<DocumentNavigationIndexPort.NavigationSectionHit> hits = navigationIndexService.searchSections(
            documentId,
            query,
            detectFacet(query),
            "",
            query,
            5
        );
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        return graphService.findSectionById(documentId, hits.get(0).nodeId());
    }

    private void collectQuotedPhrases(LinkedHashSet<String> phrases, String text) {
        Matcher matcher = QUOTED_TEXT_PATTERN.matcher(safeText(text));
        while (matcher.find()) {
            String phrase = matcher.group(1);
            if (StrUtil.isNotBlank(phrase)) {
                phrases.add(phrase.trim());
            }
        }
    }

    private boolean hasExplicitSectionAnchor(String question) {
        if (SECTION_CODE_PATTERN.matcher(question).find()) {
            return true;
        }
        if (CHINESE_SECTION_REFERENCE_PATTERN.matcher(question).find()) {
            return true;
        }
        return QUOTED_TEXT_PATTERN.matcher(question).find();
    }

    private boolean hasSectionAnchor(QueryUnderstandingResult queryUnderstanding) {
        return queryUnderstanding != null
            && queryUnderstanding.getSectionAnchors() != null
            && queryUnderstanding.getSectionAnchors().stream().anyMatch(StrUtil::isNotBlank);
    }

    private double confidence(QueryUnderstandingResult queryUnderstanding) {
        if (queryUnderstanding == null) {
            return 0D;
        }
        return normalizeConfidence(queryUnderstanding.getConfidence());
    }

    private double normalizeConfidence(double confidence) {
        // 有些模型可能输出 86 表示 86%，这里统一折算成 0.86。
        if (confidence > 1D) {
            return confidence / 100D;
        }
        return confidence;
    }

    private Integer resolveExplicitItemIndex(String question) {
        Matcher stepMatcher = STEP_REFERENCE_PATTERN.matcher(safeText(question));
        if (stepMatcher.find()) {
            return parseChineseNumber(stepMatcher.group(1));
        }
        Matcher ordinalMatcher = ORDINAL_REFERENCE_PATTERN.matcher(safeText(question));
        if (ordinalMatcher.find()) {
            return parseChineseNumber(ordinalMatcher.group(1));
        }
        return null;
    }

    private Integer parseChineseNumber(String text) {
        String normalized = safeText(text);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(normalized);
        }
        Map<Character, Integer> digitMap = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9
        );
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.startsWith("十") && normalized.length() == 2) {
            return 10 + digitMap.getOrDefault(normalized.charAt(1), 0);
        }
        if (normalized.endsWith("十") && normalized.length() == 2) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10;
        }
        if (normalized.contains("十") && normalized.length() == 3) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10 + digitMap.getOrDefault(normalized.charAt(2), 0);
        }
        return digitMap.getOrDefault(normalized.charAt(0), null);
    }

    private List<String> normalizeSubQuestions(RagRewriteResult rewriteResult, String fallbackQuestion) {
        if (rewriteResult == null || rewriteResult.getSubQuestions() == null || rewriteResult.getSubQuestions().isEmpty()) {
            return List.of(fallbackQuestion);
        }
        return rewriteResult.getSubQuestions().stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private String detectFacet(String question) {
        String normalized = safeText(question);
        if (SECTION_CODE_PATTERN.matcher(normalized).find()
            || CHINESE_SECTION_REFERENCE_PATTERN.matcher(normalized).find()
            || QUOTED_TEXT_PATTERN.matcher(normalized).find()) {
            return "章节";
        }
        if (STEP_REFERENCE_PATTERN.matcher(normalized).find()
            || ORDINAL_REFERENCE_PATTERN.matcher(normalized).find()) {
            return "步骤";
        }
        return "";
    }

    private String normalize(String text) {
        return safeText(text).replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"']+", "").toLowerCase();
    }

    private String firstNonBlank(String left, String right) {
        if (StrUtil.isNotBlank(left)) {
            return left.trim();
        }
        return StrUtil.blankToDefault(right, "");
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
