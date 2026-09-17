package org.smartledge.ai.chatagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.agent.AgentToolContext;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.RankFeatureBundle;
import org.smartledge.ai.chatagent.rag.model.RetrievalChannelPlan;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.service.TaskInfo;
import org.smartledge.ai.chatagent.support.StreamEventMetadata;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.rag.runtime.model.DocumentRetrieveRequest;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.smartledge.ai.rag.runtime.port.DocumentEvidencePort;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.smartledge.enums.RetrievalChannelEnum;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSearchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("NONE 或空范围返回 NO_SCOPE，且不访问检索端口")
    void noScopeFailClosed() throws Exception {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        DocumentEvidencePort port = new FakePort((request) -> {
            called.set(true);
            return List.of();
        });
        KnowledgeSearchTool tool = new KnowledgeSearchTool(port, new ChatRagProperties(), mapper);
        String body = tool.execute("{\"query\":\"请假\"}", context(KnowledgeBaseSelectionSnapshot.none(null), authorizedPlan()));
        JsonNode root = mapper.readTree(body);
        assertThat(root.get("status").asText()).isEqualTo("REJECTED");
        assertThat(root.get("reason").asText()).isEqualTo("NO_SCOPE");
        assertThat(root.get("citationEligible").asBoolean()).isFalse();
        assertThat(called.get()).isFalse();
    }

    @Test
    @DisplayName("有范围但没有当轮 Plan 时 fail closed")
    void noPlanFailClosed() throws Exception {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        DocumentEvidencePort port = new FakePort((request) -> {
            called.set(true);
            return List.of();
        });
        KnowledgeBaseSelectionSnapshot snapshot = KnowledgeBaseSelectionSnapshot.builder()
            .selectionMode(KnowledgeBaseSelectionMode.SELECTED)
            .allowedDocumentIds(List.of(7L))
            .allowedTaskIds(List.of(8L))
            .build();
        KnowledgeSearchTool tool = new KnowledgeSearchTool(port, new ChatRagProperties(), mapper);
        JsonNode root = mapper.readTree(tool.execute("{\"query\":\"请假\"}", context(snapshot, null)));
        assertThat(root.get("status").asText()).isEqualTo("REJECTED");
        assertThat(root.get("reason").asText()).isEqualTo("NO_PLAN");
        assertThat(called.get()).isFalse();
    }

    @Test
    @DisplayName("只把快照 allowed IDs 交给检索，原文块命中进入 SOURCE 且可引用")
    void consumesAuthorizedPlanAndMarksSource() throws Exception {
        AtomicReference<DocumentRetrieveRequest> seen = new AtomicReference<>();
        DocumentEvidencePort port = new FakePort((request) -> {
            seen.set(request);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 7L);
            metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, 8L);
            metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, 9L);
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "手册");
            metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TEXT");
            metadata.put(DocumentKnowledgeMetadataKeys.SCORE, 0.8D);
            return List.of(RetrievalDocument.builder().id("hit").text("请假需提前三天").metadata(metadata).score(0.8D).build());
        });
        KnowledgeBaseSelectionSnapshot snapshot = KnowledgeBaseSelectionSnapshot.builder()
            .selectionMode(KnowledgeBaseSelectionMode.SELECTED)
            .allowedDocumentIds(List.of(7L))
            .allowedTaskIds(List.of(8L))
            .build();
        AgentToolContext context = context(snapshot, authorizedPlan());
        KnowledgeSearchTool tool = new KnowledgeSearchTool(port, new ChatRagProperties(), mapper);
        JsonNode root = mapper.readTree(tool.execute("{\"query\":\"请假\"}", context));
        assertThat(root.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(root.get("pool").asText()).isEqualTo("SOURCE");
        assertThat(root.get("citationEligible").asBoolean()).isTrue();
        assertThat(root.get("hits").get(0).get("evidenceKind").asText()).isEqualTo("CHUNK");
        assertThat(root.get("hits").get(0).get("citationEligible").asBoolean()).isTrue();
        assertThat(seen.get().resolvedDocumentIds()).containsExactly(7L);
        assertThat(seen.get().resolvedTaskIds()).containsExactly(8L);
        assertThat(context.toolOutcomes()).isEmpty();
        assertThat(context.knowledgeScope().getAllowedDocumentIds()).containsExactly(7L);
        assertThat(context.authorizedRetrievalPlan().getCandidateWindow()).isEqualTo(8);
    }

    @Test
    @DisplayName("无稳定 identity 的命中不能进入 retrieved 引用")
    void noIdentityStaysOutOfRetrieved() throws Exception {
        DocumentEvidencePort port = new FakePort((request) -> List.of(
            RetrievalDocument.builder()
                .id("nav")
                .text("目录壳")
                .metadata(new HashMap<>(Map.of(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "STRUCTURE_NAVIGATION")))
                .score(0.1D)
                .build()
        ));
        KnowledgeBaseSelectionSnapshot snapshot = KnowledgeBaseSelectionSnapshot.builder()
            .selectionMode(KnowledgeBaseSelectionMode.SELECTED)
            .allowedDocumentIds(List.of(7L))
            .allowedTaskIds(List.of(8L))
            .build();
        AgentToolContext context = context(snapshot, authorizedPlan());
        KnowledgeSearchTool tool = new KnowledgeSearchTool(port, new ChatRagProperties(), mapper);
        JsonNode root = mapper.readTree(tool.execute("{\"query\":\"请假\"}", context));
        assertThat(root.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(root.get("hits").get(0).get("citationEligible").asBoolean()).isFalse();
        assertThat(context.references()).isEmpty();
    }

    @Test
    @DisplayName("生产源码不得再 builder 第二份 RetrievalPlan")
    void sourceDoesNotBuilderSecondPlan() throws Exception {
        Path source = Path.of("src/main/java/org/smartledge/ai/chatagent/tool/KnowledgeSearchTool.java");
        String text = Files.readString(source);
        assertThat(text).doesNotContain("RetrievalPlan.builder()");
    }

    private AgentToolContext context(KnowledgeBaseSelectionSnapshot snapshot, RetrievalPlan plan) {
        ConversationExecutionPlan executionPlan = plan == null ? null : ConversationExecutionPlan.builder()
            .retrievalPlan(plan)
            .build();
        TaskInfo task = new TaskInfo(
            "conv",
            3L,
            "请假",
            ChatQueryMode.OPEN_CHAT,
            "trace",
            1L,
            null,
            "",
            null,
            snapshot,
            LocalDate.now(),
            "",
            executionPlan,
            new ChatDebugTrace(),
            null,
            Sinks.many().unicast().onBackpressureBuffer(),
            new StreamEventMetadata("conv", 3L),
            "lease",
            "owner",
            Collections.synchronizedList(new ArrayList<>()),
            Collections.synchronizedList(new ArrayList<>()),
            ConcurrentHashMap.newKeySet(),
            System.currentTimeMillis()
        );
        return new AgentToolContext(task);
    }

    private RetrievalPlan authorizedPlan() {
        return RetrievalPlan.builder()
            .candidateWindow(8)
            .rankFeatures(RankFeatureBundle.builder().rankWeight(1D).build())
            .channels(List.of(
                RetrievalChannelPlan.builder()
                    .channelName(RetrievalChannelEnum.VECTOR.getName())
                    .enabled(true)
                    .topK(8)
                    .timeoutMs(1000L)
                    .budget(8)
                    .weight(1D)
                    .build(),
                RetrievalChannelPlan.builder()
                    .channelName(RetrievalChannelEnum.KEYWORD.getName())
                    .enabled(true)
                    .topK(8)
                    .timeoutMs(1000L)
                    .budget(8)
                    .weight(1D)
                    .build()
            ))
            .build();
    }

    private interface SearchFn {
        List<RetrievalDocument> search(DocumentRetrieveRequest request);
    }

    private static final class FakePort implements DocumentEvidencePort {
        private final SearchFn searchFn;

        private FakePort(SearchFn searchFn) {
            this.searchFn = searchFn;
        }

        @Override
        public List<org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor> listRetrievableDocuments() {
            return List.of();
        }

        @Override
        public List<org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(java.util.Collection<Long> knowledgeBaseIds) {
            return List.of();
        }

        @Override
        public List<RetrievalDocument> vectorSearch(DocumentRetrieveRequest request) {
            return searchFn.search(request);
        }

        @Override
        public List<RetrievalDocument> keywordSearch(DocumentRetrieveRequest request) {
            return searchFn.search(request);
        }

        @Override
        public List<RetrievalDocument> elevateToParentBlocks(List<RetrievalDocument> childDocuments, int maxChars) {
            return List.of();
        }
    }
}
