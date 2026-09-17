package org.smartledge.ai.chatagent.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.chatagent.data.SuperAgentChatExchange;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatExchangeMapper;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.chatagent.service.ConversationTraceStageStore;
import org.smartledge.ai.chatagent.service.RetrievalObserveStore;
import org.smartledge.enums.ChatTurnStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
class MybatisEvaluationExchangeSnapshotFactSource implements EvaluationExchangeSnapshotFactSource {

    private static final TypeReference<List<SearchReference>> REFERENCES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final SuperAgentChatExchangeMapper exchangeMapper;
    private final ConversationTraceStageStore traceStageStore;
    private final RetrievalObserveStore retrievalObserveStore;
    private final ObjectMapper objectMapper;

    MybatisEvaluationExchangeSnapshotFactSource(SuperAgentChatExchangeMapper exchangeMapper,
                                                ConversationTraceStageStore traceStageStore,
                                                RetrievalObserveStore retrievalObserveStore,
                                                ObjectMapper objectMapper) {
        this.exchangeMapper = exchangeMapper;
        this.traceStageStore = traceStageStore;
        this.retrievalObserveStore = retrievalObserveStore;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, EvaluationExchangeSnapshotFacts> load(List<Long> exchangeIds) {
        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectBatchIds(exchangeIds);
        if (exchanges == null || exchanges.isEmpty()) {
            return Map.of();
        }
        Map<Long, EvaluationExchangeSnapshotFacts> facts = new LinkedHashMap<>();
        for (SuperAgentChatExchange exchange : exchanges) {
            if (exchange == null || exchange.getId() == null) {
                continue;
            }
            String conversationId = safe(exchange.getConversationId());
            long exchangeId = exchange.getId();
            List<String> factErrors = new ArrayList<>();
            List<String> selectedKnowledgeBaseIds = readStringList(
                exchange.getSelectedKnowledgeBaseIdsJson(),
                factErrors
            );
            validateConfigSnapshot(exchange.getRetrievalConfigSnapshotJson(), factErrors);
            ChatDebugTrace debugTrace = readDebugTrace(exchange.getDebugTraceJson(), factErrors);
            List<SearchReference> references = readReferences(exchange.getReferenceList(), factErrors);
            facts.put(exchangeId, new EvaluationExchangeSnapshotFacts(
                exchangeId,
                conversationId,
                safe(exchange.getQuestion()),
                safe(exchange.getAnswer()),
                ChatTurnStatus.fromCode(exchange.getTurnStatus()),
                safe(exchange.getErrorMessage()),
                safe(exchange.getKnowledgeBaseSelectionMode()),
                selectedKnowledgeBaseIds,
                safe(exchange.getRetrievalConfigSnapshotJson()),
                debugTrace,
                references,
                toInstant(exchange.getCreateTime()),
                toInstant(exchange.getEditTime()),
                traceStageStore.listStageViews(conversationId, exchangeId),
                retrievalObserveStore.listChannelExecutions(conversationId, exchangeId),
                retrievalObserveStore.listResults(conversationId, exchangeId),
                factErrors
            ));
        }
        return Map.copyOf(facts);
    }

    private List<SearchReference> readReferences(String json, List<String> factErrors) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<SearchReference> references = objectMapper.readValue(json, REFERENCES_TYPE);
            return references == null ? List.of() : List.copyOf(references);
        }
        catch (Exception exception) {
            factErrors.add("INVALID_ARCHIVE_REFERENCES_JSON");
            return List.of();
        }
    }

    private List<String> readStringList(String json, List<String> factErrors) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST_TYPE);
            return values == null ? List.of() : List.copyOf(values);
        }
        catch (Exception exception) {
            factErrors.add("INVALID_SELECTED_KNOWLEDGE_BASE_IDS_JSON");
            return List.of();
        }
    }

    private ChatDebugTrace readDebugTrace(String json, List<String> factErrors) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ChatDebugTrace.class);
        }
        catch (Exception exception) {
            factErrors.add("INVALID_DEBUG_TRACE_JSON");
            return null;
        }
    }

    private void validateConfigSnapshot(String json, List<String> factErrors) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            if (!objectMapper.readTree(json).isObject()) {
                factErrors.add("INVALID_RETRIEVAL_CONFIG_JSON");
            }
        }
        catch (Exception exception) {
            factErrors.add("INVALID_RETRIEVAL_CONFIG_JSON");
        }
    }

    private Instant toInstant(java.util.Date date) {
        return date == null ? null : date.toInstant();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
