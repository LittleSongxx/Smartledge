package org.smartledge.ai.chatagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.agent.AgentToolContext;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.chatagent.model.memory.LongTermMemoryFact;
import org.smartledge.ai.chatagent.service.LongTermMemoryStore;
import org.smartledge.ai.chatagent.service.TaskInfo;
import org.smartledge.ai.chatagent.support.StreamEventMetadata;
import org.smartledge.enums.ChatQueryMode;
import reactor.core.publisher.Sinks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class RememberFactToolTest {

    @Test
    @DisplayName("缺会话时 REJECTED，合法写入走 store")
    void rejectsWithoutConversationAndWritesExplicitFact() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LongTermMemoryStore store = new LongTermMemoryStore() {
            @Override
            public java.util.List<LongTermMemoryFact> listActiveFacts(String conversationId, int limit) {
                return java.util.List.of();
            }

            @Override
            public LongTermMemoryFact rememberExplicit(String conversationId,
                                                       Long userId,
                                                       String entityKey,
                                                       String factText,
                                                       long exchangeId) {
                return new LongTermMemoryFact(1L, conversationId, userId, entityKey, factText,
                    LongTermMemoryFact.SOURCE_USER_EXPLICIT, LongTermMemoryFact.LIFECYCLE_ACTIVE, exchangeId, 1);
            }
        };
        RememberFactTool tool = new RememberFactTool(store, mapper);

        JsonNode rejected = mapper.readTree(tool.execute(
            "{\"entityKey\":\"format\",\"factText\":\"表格\"}",
            context("")
        ));
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("reason").asText()).isEqualTo("NO_CONVERSATION");

        JsonNode accepted = mapper.readTree(tool.execute(
            "{\"entityKey\":\"format\",\"factText\":\"表格\"}",
            context("conv-1")
        ));
        assertThat(accepted.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(accepted.get("entityKey").asText()).isEqualTo("format");
    }

    private AgentToolContext context(String conversationId) {
        TaskInfo task = new TaskInfo(
            conversationId,
            9L,
            "记住用表格",
            ChatQueryMode.OPEN_CHAT,
            "trace",
            1L,
            88L,
            null,
            "",
            null,
            null,
            LocalDate.now(),
            "",
            null,
            new ChatDebugTrace(),
            null,
            Sinks.many().unicast().onBackpressureBuffer(),
            new StreamEventMetadata(conversationId, 9L),
            "lease",
            "owner",
            Collections.synchronizedList(new ArrayList<>()),
            Collections.synchronizedList(new ArrayList<>()),
            ConcurrentHashMap.newKeySet(),
            System.currentTimeMillis()
        );
        return new AgentToolContext(task);
    }
}
