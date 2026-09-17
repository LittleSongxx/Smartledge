package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 回答阶段最终使用的历史上下文
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerHistoryContext {

    private String renderedText;

    private String structuredContext;

    private String recentContext;

    @Builder.Default
    private List<EvidenceAnchor> evidenceAnchors = new ArrayList<>();

    private String resolvedTopic;

    private boolean followUpQuestion;

    private Integer totalBudget;

    private Integer recentBudget;

    private Integer structuredBudget;

    public boolean isEmpty() {
        return (renderedText == null || renderedText.isBlank())
            && (evidenceAnchors == null || evidenceAnchors.isEmpty());
    }
}
