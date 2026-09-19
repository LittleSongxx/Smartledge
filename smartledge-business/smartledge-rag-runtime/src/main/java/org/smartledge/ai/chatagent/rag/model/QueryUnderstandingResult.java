package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询理解结果。只承载受控建议，不直接决定最终答案。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryUnderstandingResult {

    @Builder.Default
    private QueryType queryType = QueryType.DOCUMENT_QA;

    @Builder.Default
    private List<RetrievalIntent> channels = new ArrayList<>();

    @Builder.Default
    private List<String> entities = new ArrayList<>();

    @Builder.Default
    private List<String> targetEntities = new ArrayList<>();

    @Builder.Default
    private List<String> excludedEntities = new ArrayList<>();

    /**
     * 文档范围建议（advisory）：当前原始问题点名的产品/机型/文档名字面名称。
     * Java RetrievalPlan 独立授权（原问题 grounding + 置信度 + 与文档名唯一匹配）后才收窄范围。
     */
    @Builder.Default
    private List<String> documentScopeSuggestions = new ArrayList<>();

    @Builder.Default
    private List<String> sectionAnchors = new ArrayList<>();

    private StructureNavigationIntent structureNavigationIntent;

    @Builder.Default
    private List<String> tableOps = new ArrayList<>();

    @Builder.Default
    private AnswerShapePlan answerShapePlan = AnswerShapePlan.empty();

    private double confidence;

    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    private String source;
}
