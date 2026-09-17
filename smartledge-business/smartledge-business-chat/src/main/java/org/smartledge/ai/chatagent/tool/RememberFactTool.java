package org.smartledge.ai.chatagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.chatagent.agent.AgentTool;
import org.smartledge.ai.chatagent.agent.AgentToolCallStatus;
import org.smartledge.ai.chatagent.agent.AgentToolContext;
import org.smartledge.ai.chatagent.model.memory.LongTermMemoryFact;
import org.smartledge.ai.chatagent.service.LongTermMemoryStore;
import org.smartledge.ai.rag.runtime.model.ChatToolDefinition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RememberFactTool implements AgentTool {

    public static final String NAME = "remember_fact";

    private final LongTermMemoryStore store;
    private final ObjectMapper mapper;

    public RememberFactTool(LongTermMemoryStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    public ChatToolDefinition definition() {
        return new ChatToolDefinition(
            NAME,
            "把用户明确要求记住的稳定事实写入长期记忆。必须传 entityKey 与 factText。同键新值会取代旧值，不会静默覆盖。",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "entityKey", Map.of("type", "string"),
                    "factText", Map.of("type", "string")
                ),
                "required", List.of("entityKey", "factText")
            )
        );
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(8);
    }

    @Override
    public String execute(String rawArguments, AgentToolContext context) throws Exception {
        context.checkActive();
        if (context.conversationId() == null || context.conversationId().isBlank()) {
            return envelope(AgentToolCallStatus.REJECTED.name(), "NO_CONVERSATION", null);
        }
        JsonNode root = rawArguments == null || rawArguments.isBlank()
            ? mapper.createObjectNode()
            : mapper.readTree(rawArguments);
        String entityKey = text(root, "entityKey");
        String factText = text(root, "factText");
        if (entityKey.isBlank() || factText.isBlank()) {
            return envelope(AgentToolCallStatus.REJECTED.name(), "INVALID_ARGUMENT", null);
        }
        LongTermMemoryFact fact = store.rememberExplicit(
            context.conversationId(),
            context.userId(),
            entityKey,
            factText,
            context.exchangeId()
        );
        context.markToolUsed(NAME);
        return envelope(AgentToolCallStatus.SUCCEEDED.name(), fact.version() > 1 ? "SUPERSEDED" : "INSERTED", fact);
    }

    private String envelope(String status, String reason, LongTermMemoryFact fact) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("reason", reason);
        if (fact != null) {
            body.put("entityKey", fact.entityKey());
            body.put("version", fact.version());
            body.put("lifecycle", fact.lifecycle());
        }
        try {
            return mapper.writeValueAsString(body);
        }
        catch (Exception exception) {
            return "{\"status\":\"" + status + "\",\"reason\":\"" + reason + "\"}";
        }
    }

    private String text(JsonNode root, String field) {
        if (root == null || !root.hasNonNull(field)) {
            return "";
        }
        return root.get(field).asText("").trim();
    }
}
