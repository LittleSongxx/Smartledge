package org.smartledge.ai.chatagent.evaluation;

import org.smartledge.ai.chatagent.model.ChannelExecutionView;
import org.smartledge.ai.chatagent.model.RetrievalResultView;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageView;
import org.smartledge.enums.ChatTurnStatus;

import java.time.Instant;
import java.util.List;

record EvaluationExchangeSnapshotFacts(
    long exchangeId,
    String conversationId,
    String question,
    String answer,
    ChatTurnStatus status,
    String errorMessage,
    String knowledgeBaseSelectionMode,
    List<String> selectedKnowledgeBaseIds,
    String retrievalConfigSnapshotJson,
    ChatDebugTrace debugTrace,
    List<SearchReference> archiveReferences,
    Instant createdAt,
    Instant terminalAt,
    List<ConversationTraceStageView> stages,
    List<ChannelExecutionView> channelExecutions,
    List<RetrievalResultView> retrievalResults,
    List<String> factErrors
) {

    EvaluationExchangeSnapshotFacts {
        selectedKnowledgeBaseIds = selectedKnowledgeBaseIds == null ? List.of() : List.copyOf(selectedKnowledgeBaseIds);
        archiveReferences = archiveReferences == null ? List.of() : List.copyOf(archiveReferences);
        stages = stages == null ? List.of() : List.copyOf(stages);
        channelExecutions = channelExecutions == null ? List.of() : List.copyOf(channelExecutions);
        retrievalResults = retrievalResults == null ? List.of() : List.copyOf(retrievalResults);
        factErrors = factErrors == null ? List.of() : List.copyOf(factErrors);
    }
}
