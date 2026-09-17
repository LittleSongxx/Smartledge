package org.smartledge.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.chatagent.model.memory.ConversationSummaryPayload;

import java.time.Instant;

/**
 * @description: 视图对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMemorySummaryView {

    private String conversationId;

    private boolean compressionApplied;

    private long coveredExchangeId;

    private int coveredExchangeCount;

    private int compressionCount;

    private int summaryVersion;

    private String summaryText;

    private ConversationSummaryPayload summaryPayload;

    private Instant lastSourceEditTime;

    private Instant updatedAt;
}
