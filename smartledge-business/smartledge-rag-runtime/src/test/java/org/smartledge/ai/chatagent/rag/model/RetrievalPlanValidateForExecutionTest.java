package org.smartledge.ai.chatagent.rag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.smartledge.enums.RetrievalChannelEnum;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalPlanValidateForExecutionTest {

    @Test
    @DisplayName("完整计划可以通过执行校验")
    void validPlanPasses() {
        assertThatCode(() -> validPlan().validateForExecution()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("文档范围越出 allowed 时 fail closed")
    void documentScopeMustStayInsideAllowed() {
        RetrievalPlan plan = validPlan();
        plan.setDocumentScope(List.of(1L, 99L));
        plan.getRoutePlan().setAuthorizedDocumentIds(List.of(1L, 99L));
        assertThatThrownBy(plan::validateForExecution)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allowed document scope");
    }

    @Test
    @DisplayName("NONE 知识范围不可执行")
    void noneScopeRejected() {
        RetrievalPlan plan = validPlan();
        plan.setScopeMode(KnowledgeBaseSelectionMode.NONE);
        assertThatThrownBy(plan::validateForExecution)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scope mode");
    }

    @Test
    @DisplayName("缺少 execution query 不可执行")
    void missingExecutionQueryRejected() {
        RetrievalPlan plan = validPlan();
        plan.getQuestionPlan().setExecutionQueries(new ArrayList<>());
        assertThatThrownBy(plan::validateForExecution)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("execution query");
    }

    @Test
    @DisplayName("final budget 不能大于 rerank window")
    void budgetMustFitWindow() {
        RetrievalPlan plan = validPlan();
        plan.setFinalEvidenceBudget(9);
        assertThatThrownBy(plan::validateForExecution)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("final evidence budget");
    }

    private RetrievalPlan validPlan() {
        List<Long> documents = List.of(1L, 2L);
        List<Long> tasks = List.of(11L, 22L);
        return RetrievalPlan.builder()
            .questionPlan(RetrievalQuestionPlan.builder()
                .currentQuestion("请假规则")
                .rewrittenQuestion("请假规则")
                .normalizedQuery("请假规则")
                .executionQueries(List.of(RetrievalExecutionQuery.builder()
                    .index(0)
                    .sourceQuestion("请假规则")
                    .normalizedQuery("请假规则")
                    .executionQuery("请假规则")
                    .build()))
                .build())
            .chatMode(ChatQueryMode.AUTO_DOCUMENT)
            .primaryIntent(RetrievalIntent.GENERAL)
            .scopeMode(KnowledgeBaseSelectionMode.SELECTED)
            .knowledgeBaseIds(List.of(8L))
            .allowedDocumentScope(documents)
            .documentScope(documents)
            .taskScope(tasks)
            .metadataFilters(RetrievalMetadataFilters.builder().build())
            .evidenceApplicabilityPlan(EvidenceApplicabilityPlan.advisory(List.of(), List.of(), "NONE", "advisory"))
            .channels(List.of(channel(RetrievalChannelEnum.VECTOR.getName()), channel(RetrievalChannelEnum.KEYWORD.getName())))
            .tableIntent(TableIntent.builder().source("TEST").build())
            .graphIntent(GraphIntent.builder().source("TEST").build())
            .raptorIntent(RaptorIntent.builder().source("TEST").build())
            .routePlan(RetrievalRoutePlan.builder()
                .source("TEST")
                .status("READY")
                .authorizationMode(RouteScopeAuthorizationMode.KNOWLEDGE_BASE_ALLOWED_SCOPE)
                .scopeAuthorizationReason("snapshot")
                .authorizedDocumentIds(documents)
                .authorizedTaskIds(tasks)
                .topDocumentHintId(1L)
                .topTaskHintId(11L)
                .build())
            .rankFeatures(RankFeatureBundle.builder()
                .rankWeight(1D)
                .originalScoreWeight(0.08D)
                .metadataBoostWeight(0.04D)
                .maxMetadataBoost(1D)
                .build())
            .candidateWindow(8)
            .rerankWindow(6)
            .rerankRequested(false)
            .finalEvidenceBudget(4)
            .subQuestionTimeoutMs(1000L)
            .build();
    }

    private RetrievalChannelPlan channel(String name) {
        return RetrievalChannelPlan.builder()
            .channelName(name)
            .enabled(true)
            .topK(8)
            .timeoutMs(1000L)
            .budget(8)
            .weight(1D)
            .minimumScore(0D)
            .relativeScoreFloor(0D)
            .build();
    }
}
