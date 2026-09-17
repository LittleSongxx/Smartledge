package org.smartledge.ai.chatagent.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.rag.model.QueryType;
import org.smartledge.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.smartledge.ai.chatagent.rag.model.RetrievalChannelPlan;
import org.smartledge.ai.chatagent.rag.model.RetrievalIntent;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.model.RouteScopeAuthorizationMode;
import org.smartledge.ai.chatagent.rag.model.StructureNavigationIntent;
import org.smartledge.ai.chatagent.rag.model.StructureNavigationOperation;
import org.smartledge.ai.rag.runtime.model.RagRuntimeOptions;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.smartledge.enums.RetrievalChannelEnum;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalPlanAssemblerChannelGateTest {

    private final RetrievalPlanAssembler assembler = new RetrievalPlanAssembler();

    @Test
    @DisplayName("GENERAL 问句默认只开 vector+keyword，不开 table/graph/raptor")
    void generalQueryDisablesExpensiveChannels() {
        RetrievalPlan plan = assembler.assemble(input(QueryType.DOCUMENT_QA, List.of(), "请假规则"));
        assertThat(enabled(plan, RetrievalChannelEnum.VECTOR)).isTrue();
        assertThat(enabled(plan, RetrievalChannelEnum.KEYWORD)).isTrue();
        assertThat(enabled(plan, RetrievalChannelEnum.TABLE)).isFalse();
        assertThat(enabled(plan, RetrievalChannelEnum.GRAPH_RAG)).isFalse();
        assertThat(enabled(plan, RetrievalChannelEnum.RAPTOR)).isFalse();
    }

    @Test
    @DisplayName("TABLE intent 才打开 table 通道")
    void tableIntentEnablesTableChannel() {
        RetrievalPlan plan = assembler.assemble(input(QueryType.TABLE_QUERY, List.of(RetrievalIntent.TABLE), "统计表格"));
        assertThat(enabled(plan, RetrievalChannelEnum.TABLE)).isTrue();
        assertThat(enabled(plan, RetrievalChannelEnum.GRAPH_RAG)).isFalse();
        assertThat(enabled(plan, RetrievalChannelEnum.RAPTOR)).isFalse();
    }

    @Test
    @DisplayName("问句年份与书名进入 hint，不收缩 AUTO 文档范围")
    void yearAndDocumentNameHintsDoNotShrinkScope() {
        RetrievalPlan plan = assembler.assemble(input(
            QueryType.DOCUMENT_QA,
            List.of(),
            "《XX-200智能网关产品手册》在 2026 年有哪些能力"
        ));
        assertThat(plan.getDocumentScope()).containsExactly(1L, 2L);
        assertThat(plan.getMetadataFilters().getYearHints()).contains("2026");
        assertThat(plan.getMetadataFilters().getDocumentNameHints())
            .anyMatch(name -> name.contains("XX-200"));
    }

    @Test
    @DisplayName("未授权结构导航时，问句 1.2/第3条 不得进入硬过滤 sectionPathHints")
    void unauthorizedSectionRegexDoesNotBecomeHardFilter() {
        RetrievalPlan plan = assembler.assemble(input(
            QueryType.DOCUMENT_QA,
            List.of(),
            "《差旅手册》在 2026 年 1.2 节和第3条怎么规定"
        ));
        assertThat(plan.getMetadataFilters().getSectionPathHints()).isEmpty();
        assertThat(plan.getMetadataFilters().getYearHints()).contains("2026");
        assertThat(plan.getMetadataFilters().getDocumentNameHints())
            .anyMatch(name -> name.contains("差旅手册"));
    }

    @Test
    @DisplayName("已授权结构导航时，章节 hint 仍可作为硬过滤")
    void authorizedStructureNavigationKeepsSectionHardHints() {
        RetrievalPlan plan = assembler.assemble(inputWithUnderstanding(
            QueryUnderstandingResult.builder()
                .queryType(QueryType.STRUCTURE_NAVIGATION)
                .channels(List.of())
                .source("test")
                .confidence(0.9D)
                .structureNavigationIntent(StructureNavigationIntent.builder()
                    .anchorSectionPath("第3条")
                    .sectionAnchors(List.of("第3条"))
                    .confidence(0.9D)
                    .operations(List.of(StructureNavigationOperation.CURRENT_SECTION))
                    .build())
                .build(),
            "请打开第3条"
        ));
        assertThat(plan.getMetadataFilters().getSectionPathHints()).contains("第3条");
    }

    private boolean enabled(RetrievalPlan plan, RetrievalChannelEnum channel) {
        return plan.getChannels().stream()
            .filter(item -> channel.getName().equals(item.getChannelName()))
            .findFirst()
            .map(RetrievalChannelPlan::isEnabled)
            .orElse(false);
    }

    private RetrievalPlanAssembler.AssemblyInput input(QueryType queryType,
                                                       List<RetrievalIntent> channels,
                                                       String question) {
        return RetrievalPlanAssembler.AssemblyInput.builder()
            .chatMode(ChatQueryMode.AUTO_DOCUMENT)
            .originalQuestion(question)
            .rewrittenQuestion(question)
            .queryUnderstanding(QueryUnderstandingResult.builder()
                .queryType(queryType)
                .channels(channels)
                .source("test")
                .confidence(0.9D)
                .build())
            .knowledgeBaseSelectionMode(KnowledgeBaseSelectionMode.SELECTED)
            .knowledgeBaseIds(List.of(8L))
            .allowedDocumentIds(List.of(1L, 2L))
            .documentScope(List.of(1L, 2L))
            .taskScope(List.of(11L, 22L))
            .knowledgeRoutePlan(KnowledgeRoutePlan.builder()
                .authorizationMode(RouteScopeAuthorizationMode.KNOWLEDGE_BASE_ALLOWED_SCOPE)
                .scopeAuthorizationReason("snapshot")
                .authorizedDocumentIds(List.of(1L, 2L))
                .authorizedTaskIds(List.of(11L, 22L))
                .build())
            .runtimeOptions(RagRuntimeOptions.defaults())
            .build();
    }

    private RetrievalPlanAssembler.AssemblyInput inputWithUnderstanding(QueryUnderstandingResult understanding,
                                                                        String question) {
        return RetrievalPlanAssembler.AssemblyInput.builder()
            .chatMode(ChatQueryMode.AUTO_DOCUMENT)
            .originalQuestion(question)
            .rewrittenQuestion(question)
            .queryUnderstanding(understanding)
            .knowledgeBaseSelectionMode(KnowledgeBaseSelectionMode.SELECTED)
            .knowledgeBaseIds(List.of(8L))
            .allowedDocumentIds(List.of(1L, 2L))
            .documentScope(List.of(1L, 2L))
            .taskScope(List.of(11L, 22L))
            .knowledgeRoutePlan(KnowledgeRoutePlan.builder()
                .authorizationMode(RouteScopeAuthorizationMode.KNOWLEDGE_BASE_ALLOWED_SCOPE)
                .scopeAuthorizationReason("snapshot")
                .authorizedDocumentIds(List.of(1L, 2L))
                .authorizedTaskIds(List.of(11L, 22L))
                .build())
            .runtimeOptions(RagRuntimeOptions.defaults())
            .build();
    }
}
