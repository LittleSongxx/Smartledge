package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前轮检索问题契约。当前问题、改写结果和可继承的 typed context 分字段保存，
 * 避免把上一轮自然语言直接拼入当前 execution query。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalQuestionPlan {

    private String currentQuestion;

    private String rewrittenQuestion;

    private String normalizedQuery;

    @Builder.Default
    private List<RetrievalExecutionQuery> executionQueries = new ArrayList<>();

    private boolean followUp;

    private boolean historyInherited;

    @Builder.Default
    private String historyInheritanceSource = "NONE";

    @Builder.Default
    private List<RetrievalContextAnchor> inheritedContextAnchors = new ArrayList<>();
}
