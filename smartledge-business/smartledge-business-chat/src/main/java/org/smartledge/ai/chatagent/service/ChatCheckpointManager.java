package org.smartledge.ai.chatagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.rag.runtime.agent.AgentState;
import org.smartledge.ai.rag.runtime.agent.AgentStatePort;
import org.smartledge.ai.rag.runtime.model.ChatMessage;
import org.smartledge.database.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/** New-format state only. Schema installation is an explicit operation, never a bean side effect. */
@Component
public class ChatCheckpointManager implements AgentStatePort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    public ChatCheckpointManager(DataSource dataSource, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.mapper = mapper;
    }
    @Override public AgentState begin(String conversationId, long exchangeId) {
        if (conversationId == null || conversationId.isBlank() || exchangeId <= 0) throw new IllegalArgumentException("Agent identity");
        String stateKey = stateKey(conversationId);
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO smartledge_state (conversation_id) VALUES (?) ON DUPLICATE KEY UPDATE conversation_id=conversation_id", stateKey);
            AgentState previous = select(stateKey, conversationId, true).orElseThrow();
            if (exchangeId <= previous.exchangeId()) throw new IllegalStateException("Stale Agent exchange");
            int updated = jdbc.update("UPDATE smartledge_state SET exchange_id=?, active_exchange_id=?, version=version+1 WHERE conversation_id=? AND version=?",
                exchangeId, exchangeId, stateKey, previous.version());
            requireUpdate(updated);
            return new AgentState(conversationId, exchangeId, previous.version()+1, previous.checkpointCount(),
                previous.modelCalls(), previous.toolCalls(), previous.messages());
        });
    }
    @Override public AgentState save(AgentState expected, int modelCalls, int toolCalls, List<ChatMessage> history, boolean checkpoint) {
        if (modelCalls < expected.modelCalls() || toolCalls < expected.toolCalls()) throw new IllegalArgumentException("Counter regression");
        String json;
        try { json = mapper.writeValueAsString(history); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid Agent history", e); }
        return transaction.execute(status -> {
            long count = expected.checkpointCount() + (checkpoint ? 1 : 0);
            String stateKey = stateKey(expected.conversationId());
            requireUpdate(jdbc.update("UPDATE smartledge_state SET version=version+1, checkpoint_count=?, model_calls=?, tool_calls=?, messages_json=? WHERE conversation_id=? AND version=? AND active_exchange_id=?",
                count, modelCalls, toolCalls, json, stateKey, expected.version(), expected.exchangeId()));
            if (checkpoint) jdbc.update("INSERT INTO smartledge_checkpoint (conversation_id, checkpoint_no, exchange_id, state_version) VALUES (?,?,?,?)",
                stateKey, count, expected.exchangeId(), expected.version()+1);
            return new AgentState(expected.conversationId(), expected.exchangeId(), expected.version()+1, count, modelCalls, toolCalls, history);
        });
    }
    @Override public void finish(AgentState expected) {
        // A newer exchange wins; finishing an old run cannot close or mutate its successor.
        jdbc.update("UPDATE smartledge_state SET active_exchange_id=NULL, version=version+1 WHERE conversation_id=? AND version=? AND active_exchange_id=?",
            stateKey(expected.conversationId()), expected.version(), expected.exchangeId());
    }
    @Override public Optional<AgentState> get(String conversationId) { return select(stateKey(conversationId), conversationId, false); }
    private Optional<AgentState> select(String stateKey, String publicConversationId, boolean lock) {
        return jdbc.query("SELECT conversation_id,exchange_id,version,checkpoint_count,model_calls,tool_calls,messages_json FROM smartledge_state WHERE conversation_id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> {
                try {
                    List<ChatMessage> history = mapper.readValue(rs.getString("messages_json"), new TypeReference<List<ChatMessage>>() { });
                    return new AgentState(publicConversationId,rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getInt(5),rs.getInt(6),history);
                } catch (Exception e) { throw new IllegalStateException("Invalid Agent state", e); }
            }, stateKey).stream().findFirst();
    }
    @Override public int clear(String conversationId) {
        String stateKey = stateKey(conversationId);
        return transaction.execute(status -> {
            select(stateKey, conversationId, true);
            int removed = jdbc.update("DELETE FROM smartledge_checkpoint WHERE conversation_id=?", stateKey);
            jdbc.update("DELETE FROM smartledge_state WHERE conversation_id=?", stateKey);
            return removed;
        });
    }

    private static String stateKey(String conversationId) {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalStateException("checkpoint 缺少租户上下文");
        }
        return ConversationRuntimeKeys.checkpointKey(tenantId, conversationId);
    }
    private static void requireUpdate(int rows) { if (rows != 1) throw new IllegalStateException("Stale Agent state"); }
}
