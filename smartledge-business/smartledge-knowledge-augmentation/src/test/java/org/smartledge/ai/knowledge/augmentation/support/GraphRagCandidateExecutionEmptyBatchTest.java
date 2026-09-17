package org.smartledge.ai.knowledge.augmentation.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.knowledge.augmentation.config.GraphRagExecutionProperties;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionRequest;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionResponse;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagExtractionPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 空产出批次的观测口径测试（S22 批次 1）。
 *
 * <p>抽取请求改成供应商侧结构约束（`json_schema` + `strict`）之后，"合法但什么都没抽到"在结构上
 * 完全合规——如果它既没有日志也没有统计，少抽就会变成静默降级。这里锁定三条：
 * ① 完成但零候选的批次被计数，并作为"空产出批次占比"写进构建报告（snapshot → {@code extractorMetadata}）；
 * ② 有候选的批次不算空产出；③ 候选全部被拒绝也不算空产出（拒绝数是独立信号）。</p>
 */
class GraphRagCandidateExecutionEmptyBatchTest {

    private static final long CHUNK_A = 7001L;
    private static final long CHUNK_B = 7002L;
    private static final String FINGERPRINT = "a".repeat(64);
    private static final Map<String, Object> OPTIONS = Map.of("inputTokenBudget", 4600, "batchChunkLimit", 1,
            "maxReasonChars", 240, "maxDocumentBytes", 4000000);

    @Test
    @DisplayName("零候选批次被计数并进入构建报告，占比以完成批次为分母")
    void emptyBatchesAreCountedAndReported() {
        Map<String, Object> metadata = extract(batch -> "b2".equals(batch.getBatchId())
                ? entities(batch, List.of()) : candidates(batch));

        assertThat(metadata).containsEntry("successfulBatchCount", 2);
        assertThat(metadata).containsEntry("emptyBatchCount", 1);
        assertThat(metadata).containsEntry("emptyBatchRatio", 0.5);
    }

    @Test
    @DisplayName("有候选的批次不算空产出：占比为 0")
    void batchesWithCandidatesAreNotEmpty() {
        Map<String, Object> metadata = extract(GraphRagCandidateExecutionEmptyBatchTest::candidates);

        assertThat(metadata).containsEntry("successfulBatchCount", 2);
        assertThat(metadata).containsEntry("emptyBatchCount", 0);
        assertThat(metadata).containsEntry("emptyBatchRatio", 0.0);
    }

    @Test
    @DisplayName("候选全部被拒绝不等于空产出：拒绝数是独立信号")
    void rejectedCandidatesAreNotEmptyOutput() {
        Map<String, Object> metadata = extract(batch -> {
            GraphRagExtractionResponse response = candidates(batch);
            if ("b2".equals(batch.getBatchId())) {
                // 候选存在但来源未知：批量校验会拒绝它，而不是把它当成"模型什么都没抽"。
                response.getEntities().get(0).setSourceChunkIds(new ArrayList<>(List.of(999L)));
            }
            return response;
        });

        assertThat(metadata).containsEntry("emptyBatchCount", 0);
        assertThat(((Number) metadata.get("candidateRejectionCount")).intValue()).isGreaterThan(0);
    }

    private Map<String, Object> extract(Function<GraphRagExtractionRequest, GraphRagExtractionResponse> extraction) {
        GraphRagBatchExecutor executor = new GraphRagBatchExecutor(properties(), System::nanoTime);
        try {
            GraphRagCandidateExecution execution = new GraphRagCandidateExecution(new ScriptedPort(extraction),
                    new ObjectMapper(), executor);
            GraphRagExtractionResponse merged = execution.execute(request(), () -> {
            }, progress -> {
            });
            return merged.getMetadata();
        }
        finally {
            executor.close();
        }
    }

    private static GraphRagExtractionResponse candidates(GraphRagExtractionRequest batch) {
        return entities(batch, List.of(entity(batch)));
    }

    private static GraphRagExtractionResponse.Entity entity(GraphRagExtractionRequest batch) {
        long chunkId = ((Number) batch.getSegments().get(0).get("chunkId")).longValue();
        GraphRagExtractionResponse.Entity entity = new GraphRagExtractionResponse.Entity();
        entity.setId("e1");
        entity.setName("Alpha");
        entity.setSourceChunkIds(new ArrayList<>(List.of(chunkId)));
        return entity;
    }

    private static GraphRagExtractionResponse entities(GraphRagExtractionRequest batch,
            List<GraphRagExtractionResponse.Entity> entities) {
        GraphRagExtractionResponse response = new GraphRagExtractionResponse();
        response.setEntities(new ArrayList<>(entities));
        response.setRelations(new ArrayList<>());
        response.setEvidences(new ArrayList<>());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", GraphRagCandidateExecution.VERSION);
        metadata.put("operation", "extract");
        metadata.put("status", "completed");
        metadata.put("inputFingerprint", FINGERPRINT);
        metadata.put("configurationFingerprint", batch.getConfigurationFingerprint());
        metadata.put("planFingerprint", batch.getPlanFingerprint());
        metadata.put("batchId", batch.getBatchId());
        metadata.put("completedSourceIds", batch.getSegments().stream().map(segment -> segment.get("sourceId")).toList());
        metadata.put("promptTokensEstimate", 100);
        response.setMetadata(metadata);
        return response;
    }

    private static GraphRagExecutionProperties properties() {
        GraphRagExecutionProperties properties = new GraphRagExecutionProperties();
        properties.setWorkerThreads(2);
        properties.setQueueCapacity(8);
        properties.setDocumentConcurrency(2);
        properties.setMaxBatchAttempts(2);
        properties.setRetryBackoffMillis(0L);
        properties.setDocumentBudgetMillis(60000L);
        properties.setToolBudgetMillis(5000L);
        properties.setReserveMillis(1000L);
        properties.setMaxSplitDepth(1);
        properties.setMaxSplits(4);
        properties.setMaxTimeoutSplitsPerSource(1);
        properties.setMaxBatches(16);
        properties.setMaxCandidateBytes(16777216L);
        properties.setMaxObservationBytes(32768);
        return properties;
    }

    private static GraphRagExtractionRequest request() {
        GraphRagExtractionRequest request = new GraphRagExtractionRequest();
        request.setOperation("plan");
        request.setSourceParseTaskId(9L);
        request.setDocumentId(1L);
        request.setTaskId(2L);
        request.setInputFingerprint(FINGERPRINT);
        request.setBudgetMillis(30000L);
        request.setOptions(new LinkedHashMap<>(OPTIONS));
        GraphRagExtractionRequest.Chunk first = new GraphRagExtractionRequest.Chunk();
        first.setChunkId(CHUNK_A);
        first.setText("Alpha calls Beta.");
        GraphRagExtractionRequest.Chunk second = new GraphRagExtractionRequest.Chunk();
        second.setChunkId(CHUNK_B);
        second.setText("Beta calls Gamma.");
        request.setChunks(new ArrayList<>(List.of(first, second)));
        return request;
    }

    /** 计划响应必须与抽取向量的来源一一对应，且计划本身不含候选。 */
    private static final class ScriptedPort implements GraphRagExtractionPort {

        private final Function<GraphRagExtractionRequest, GraphRagExtractionResponse> extraction;

        private ScriptedPort(Function<GraphRagExtractionRequest, GraphRagExtractionResponse> extraction) {
            this.extraction = extraction;
        }

        @Override
        public GraphRagExtractionResponse extract(GraphRagExtractionRequest request) {
            if ("plan".equals(request.getOperation())) {
                GraphRagExtractionResponse response = new GraphRagExtractionResponse();
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("schemaVersion", GraphRagCandidateExecution.VERSION);
                metadata.put("operation", "plan");
                metadata.put("inputFingerprint", FINGERPRINT);
                metadata.put("configurationFingerprint", FINGERPRINT);
                metadata.put("planFingerprint", FINGERPRINT);
                metadata.put("offsetUnit", "unicode-code-point");
                metadata.put("inference", Map.of("options", request.getOptions(),
                        "version", GraphRagCandidateExecution.VERSION,
                        "tokenizer", "tokenizers.byte-level-unmerged.estimate.v1",
                        "promptFingerprint", FINGERPRINT, "endpointFingerprint", FINGERPRINT,
                        "model", "fixture-model", "contextTokens", 16384,
                        "outputReserve", 4096, "promptReserve", 1024));
                metadata.put("batches",
                        List.of(segmentPlan(request, CHUNK_A, "b1"), segmentPlan(request, CHUNK_B, "b2")));
                response.setMetadata(metadata);
                return response;
            }
            return extraction.apply(request);
        }

        private static Map<String, Object> segmentPlan(GraphRagExtractionRequest request, long chunkId,
                String batchId) {
            GraphRagExtractionRequest.Chunk chunk = request.getChunks().stream()
                    .filter(value -> value.getChunkId() == chunkId).findFirst().orElseThrow();
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("sourceId", "b1".equals(batchId) ? "s1" : "s2");
            segment.put("chunkId", chunk.getChunkId());
            segment.put("start", 0);
            segment.put("end", chunk.getText().codePointCount(0, chunk.getText().length()));
            segment.put("text", chunk.getText());
            segment.put("contentFingerprint", GraphRagCandidateExecution.hash(chunk.getText()));
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("batchId", batchId);
            plan.put("segments", List.of(segment));
            plan.put("promptTokensEstimate", 100);
            return plan;
        }
    }
}
