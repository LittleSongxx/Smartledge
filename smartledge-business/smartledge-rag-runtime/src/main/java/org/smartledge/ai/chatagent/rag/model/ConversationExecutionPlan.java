package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.enums.ChatQueryMode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * @description: 单轮对话执行计划
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationExecutionPlan {

    private ExecutionMode mode;

    private ChatQueryMode chatMode;

    private String originalQuestion;

    private String agentQuestion;

    private String rewriteQuestion;

    @Builder.Default
    private List<String> rewriteSubQuestions = new ArrayList<>();

    private String historySummary;

    private String longTermSummary;

    private String longTermFactsText;

    @Builder.Default
    private HistoryPlanningContext historyPlanningContext = new HistoryPlanningContext();

    private String recentHistoryTranscript;

    private String answerRecentTranscript;

    private AnswerHistoryContext answerHistoryContext;

    private DocumentNavigationDecision navigationDecision;

    private QueryUnderstandingResult queryUnderstanding;

    /**
     * 单一检索执行计划。当前文档问答与自动知识问答都通过它进入 RagRetrievalEngine，避免形成并行检索入口。
     */
    private RetrievalPlan retrievalPlan;

    private boolean historyCompressionApplied;

    private Long historyCoveredExchangeId;

    private Integer historyCoveredExchangeCount;

    private Integer historyCompressionCount;

    private LocalDate currentDate;

    private String currentDateText;

    private boolean requiresFreshSearch;

    private boolean requiresCurrentDateAnchoring;

    private String clarificationReply;

    @Builder.Default
    private List<String> clarificationOptions = new ArrayList<>();

    private String clarificationReason;

    private String noEvidenceReply;
}
