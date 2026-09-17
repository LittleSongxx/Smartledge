package org.smartledge.ai.chatagent.rag.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.chatagent.model.memory.ConversationMemoryContext;
import org.smartledge.ai.chatagent.rag.model.AnswerHistoryContext;
import org.smartledge.ai.chatagent.rag.model.EvidenceAnchor;
import org.smartledge.ai.chatagent.rag.model.HistoryPlanningContext;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 会话上下文装载结果，供编排器组装执行计划
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationContextBundle {

    private ConversationMemoryContext memoryContext;

    private HistoryPlanningContext historyPlanningContext;

    private String historySummary;

    @Builder.Default
    private List<EvidenceAnchor> recentEvidenceAnchors = new ArrayList<>();

    private AnswerHistoryContext initialAnswerHistoryContext;
}
