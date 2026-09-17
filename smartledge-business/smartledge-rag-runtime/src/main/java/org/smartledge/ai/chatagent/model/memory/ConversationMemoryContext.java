package org.smartledge.ai.chatagent.model.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 编排阶段真正使用的会话记忆上下文
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMemoryContext {

    private String assembledHistory;

    private String longTermSummary;

    @Builder.Default
    private List<LongTermMemoryFact> longTermFacts = new ArrayList<>();

    private String recentTranscript;

    private String answerRecentTranscript;

    private ConversationSummaryPayload summaryPayload;

    private Long coveredExchangeId;

    private Integer coveredExchangeCount;

    private Integer compressionCount;

    private boolean compressionApplied;

    public String renderFactsText() {
        if (longTermFacts == null || longTermFacts.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (LongTermMemoryFact fact : longTermFacts) {
            if (fact == null || !fact.active()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append("- ").append(fact.entityKey()).append("：").append(fact.factText());
        }
        return builder.toString();
    }
}
