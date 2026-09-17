package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.knowledge.indexing.port.DocumentVectorConfigurationPort;
import org.smartledge.ai.manage.service.DocumentVectorGateway;
import org.smartledge.ai.manage.support.DocumentPgVectorConstants;
import org.smartledge.ai.manage.support.DocumentTenantLookup;
import org.smartledge.ai.manage.support.PgVectorTenantOperations;
import org.smartledge.enums.DocumentManageCode;
import org.smartledge.enums.DocumentVectorStatusEnum;
import org.smartledge.enums.DocumentVectorStoreTypeEnum;
import org.smartledge.exception.SuperAgentFrameException;
import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.smartledge.database.tenant.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@Slf4j
@Service
public class DefaultDocumentVectorGateway implements DocumentVectorGateway {

    public static final int EMBEDDING_BATCH_SIZE_LIMIT = 10;

    private static final String UPSERT_SQL_TEMPLATE = """
        INSERT INTO %s
        (id, document_id, task_id, plan_id, parent_block_id, chunk_no, source_type, section_path, structure_node_id,
         structure_node_type, canonical_path, item_index, chunk_text, content_with_weight, chunk_type, title, keywords,
         questions, char_count, token_count, page_no, page_range, bbox_json, source_block_ids, embedding_model,
         metadata_json, embedding, create_time, edit_time, status, tenant_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS vector), NOW(), NOW(), ?, ?)
        ON CONFLICT (id) DO UPDATE SET
            document_id = EXCLUDED.document_id,
            task_id = EXCLUDED.task_id,
            plan_id = EXCLUDED.plan_id,
            parent_block_id = EXCLUDED.parent_block_id,
            chunk_no = EXCLUDED.chunk_no,
            source_type = EXCLUDED.source_type,
            section_path = EXCLUDED.section_path,
            structure_node_id = EXCLUDED.structure_node_id,
            structure_node_type = EXCLUDED.structure_node_type,
            canonical_path = EXCLUDED.canonical_path,
            item_index = EXCLUDED.item_index,
            chunk_text = EXCLUDED.chunk_text,
            content_with_weight = EXCLUDED.content_with_weight,
            chunk_type = EXCLUDED.chunk_type,
            title = EXCLUDED.title,
            keywords = EXCLUDED.keywords,
            questions = EXCLUDED.questions,
            char_count = EXCLUDED.char_count,
            token_count = EXCLUDED.token_count,
            page_no = EXCLUDED.page_no,
            page_range = EXCLUDED.page_range,
            bbox_json = EXCLUDED.bbox_json,
            source_block_ids = EXCLUDED.source_block_ids,
            embedding_model = EXCLUDED.embedding_model,
            metadata_json = EXCLUDED.metadata_json,
            embedding = EXCLUDED.embedding,
            edit_time = NOW(),
            status = EXCLUDED.status,
            tenant_id = EXCLUDED.tenant_id
        """;

    private static final String DELETE_BY_DOCUMENT_SQL_TEMPLATE = "DELETE FROM %s WHERE document_id = ?";

    private static final String DELETE_BY_TASK_SQL_TEMPLATE = "DELETE FROM %s WHERE document_id = ? AND task_id = ?";

    private final JdbcTemplate pgVectorJdbcTemplate;

    private final PgVectorTenantOperations pgVectorOperations;

    private final DocumentTenantLookup documentTenantLookup;

    private final ObjectProvider<EmbeddingPort> embeddingModelProvider;

    private final ObjectMapper objectMapper;

    private final DocumentVectorConfigurationPort vectorConfiguration;


    public DefaultDocumentVectorGateway(
        @Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
        PgVectorTenantOperations pgVectorOperations,
        DocumentTenantLookup documentTenantLookup,
        ObjectProvider<EmbeddingPort> embeddingModelProvider,
        ObjectMapper objectMapper,
        DocumentVectorConfigurationPort vectorConfiguration) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.pgVectorOperations = pgVectorOperations;
        this.documentTenantLookup = documentTenantLookup;
        this.embeddingModelProvider = embeddingModelProvider;
        this.objectMapper = objectMapper;
        this.vectorConfiguration = vectorConfiguration;
    }

    @Override
    public void deleteByChunkIds(Long documentId, Long taskId, List<Long> chunkIds) {
        if (documentId == null || taskId == null || CollUtil.isEmpty(chunkIds)) {
            return;
        }
        List<Long> distinctChunkIds = chunkIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (distinctChunkIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", distinctChunkIds.stream().map(ignored -> "?").toList());
        String sql = "DELETE FROM %s WHERE document_id = ? AND task_id = ? AND id IN (%s)"
            .formatted(DocumentPgVectorConstants.EMBEDDING_TABLE_NAME, placeholders);
        List<Object> arguments = new ArrayList<>();
        arguments.add(documentId);
        arguments.add(taskId);
        arguments.addAll(distinctChunkIds);
        try {
            pgVectorOperations.update(vectorTenant(documentId), sql, arguments.toArray());
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_VECTOR_FAILED.getCode(),
                "删除 PGVector 指定 chunk 数据失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void vectorize(List<SuperAgentDocumentChunk> chunkList) {

        if (CollUtil.isEmpty(chunkList)) {
            return;
        }

        EmbeddingPort embeddingModel = requireEmbeddingModel();

        List<SuperAgentDocumentChunk> validChunkList = chunkList.stream()
            .filter(chunk -> chunk != null && StrUtil.isNotBlank(embeddingInput(chunk)))
            .toList();
        if (validChunkList.isEmpty()) {
            return;
        }

        String upsertSql = UPSERT_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.EMBEDDING_TABLE_NAME);
        int batchSize = embeddingBatchSize();
        String currentEmbeddingModelName = resolveEmbeddingModelName();
        int totalBatchCount = (validChunkList.size() + batchSize - 1) / batchSize;
        int parallelism = embeddingParallelism(totalBatchCount);
        long vectorizeStartedNanos = System.nanoTime();

        log.info("开始执行文档向量化，chunkCount={}, batchSize={}, batchCount={}, parallelism={}, embeddingModel={}",
            validChunkList.size(), batchSize, totalBatchCount, parallelism, currentEmbeddingModelName);

        List<VectorBatch> batches = buildBatches(validChunkList, batchSize);
        // 向量数据的租户来自父文档：索引构建在系统上下文里跑，这里必须显式取，不能用默认租户兜底。
        Long vectorTenantId = vectorTenant(validChunkList.get(0).getDocumentId());
        if (parallelism <= 1) {
            for (VectorBatch batch : batches) {
                processBatch(embeddingModel, upsertSql, batch, totalBatchCount, currentEmbeddingModelName, vectorTenantId);
            }
        }
        else {
            ExecutorService executorService = Executors.newFixedThreadPool(parallelism, vectorThreadFactory());
            // 向量化批次跑在该调用临时创建的线程池上，池线程与索引构建线程无关：
            // 在构建线程捕获租户并随批次任务携带，否则批次内的业务表查询会 fail closed。
            Long tenantId = TenantContext.get();
            try {
                List<CompletableFuture<Void>> futures = batches.stream()
                    .map(batch -> CompletableFuture.runAsync(
                        TenantContext.propagating(
                            tenantId,
                            () -> processBatch(embeddingModel, upsertSql, batch, totalBatchCount,
                                currentEmbeddingModelName, vectorTenantId)
                        ),
                        executorService
                    ))
                    .toList();
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
            catch (CompletionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw exception;
            }
            finally {
                executorService.shutdown();
            }
        }

        log.info("文档向量化执行完成，chunkCount={}, batchSize={}, batchCount={}, parallelism={}, embeddingModel={}, costMillis={}",
            validChunkList.size(), batchSize, totalBatchCount, parallelism, currentEmbeddingModelName, elapsedMillis(vectorizeStartedNanos));
    }

    private void processBatch(EmbeddingPort embeddingModel,
                              String upsertSql,
                              VectorBatch batch,
                              int totalBatchCount,
                              String currentEmbeddingModelName,
                              Long vectorTenantId) {
        List<SuperAgentDocumentChunk> currentBatch = batch.chunks();
        log.info("开始处理 embedding 批次，batchIndex={}/{}, chunkRange=[{}, {}], currentBatchSize={}",
            batch.batchIndex(), totalBatchCount, batch.startNo(), batch.endNo(), currentBatch.size());

        long batchStartedNanos = System.nanoTime();
        long embeddingStartedNanos = System.nanoTime();
        List<float[]> embeddingList = embedBatchWithRetry(embeddingModel, batch, totalBatchCount);
        long embeddingCostMillis = elapsedMillis(embeddingStartedNanos);
        if (embeddingList.size() != currentBatch.size()) {
            throw new IllegalStateException("EmbeddingPort 返回的向量数量与 chunk 数量不一致。");
        }

        long upsertStartedNanos = System.nanoTime();
        batchUpsert(upsertSql, currentBatch, embeddingList, currentEmbeddingModelName, vectorTenantId);
        long upsertCostMillis = elapsedMillis(upsertStartedNanos);
        markSuccess(currentBatch);

        log.info("embedding 批次处理完成，batchIndex={}/{}, currentBatchSize={}, embeddingCostMillis={}, pgVectorCostMillis={}, batchCostMillis={}",
            batch.batchIndex(), totalBatchCount, currentBatch.size(), embeddingCostMillis, upsertCostMillis, elapsedMillis(batchStartedNanos));
    }

    private List<float[]> embedBatchWithRetry(EmbeddingPort embeddingModel, VectorBatch batch, int totalBatchCount) {
        int maxAttempts = embeddingBatchMaxAttempts();
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long attemptStartedNanos = System.nanoTime();
            try {
                List<float[]> embeddings = embeddingModel.embed(batch.chunks().stream()
                    .map(this::embeddingInput)
                    .toList());
                if (attempt > 1) {
                    log.info("embedding 批次重试成功，batchIndex={}/{}, attempt={}/{}, costMillis={}",
                        batch.batchIndex(), totalBatchCount, attempt, maxAttempts, elapsedMillis(attemptStartedNanos));
                }
                return embeddings;
            }
            catch (RuntimeException exception) {
                lastException = exception;
                if (attempt >= maxAttempts || !isTransientEmbeddingException(exception)) {
                    throw exception;
                }
                long backoffMillis = embeddingRetryBackoffMillis(attempt);
                log.warn("embedding 批次出现瞬时 I/O 异常，准备重试: batchIndex={}/{}, attempt={}/{}, chunkRange=[{}, {}], backoffMillis={}, message={}",
                    batch.batchIndex(),
                    totalBatchCount,
                    attempt,
                    maxAttempts,
                    batch.startNo(),
                    batch.endNo(),
                    backoffMillis,
                    rootCauseMessage(exception));
                sleepBeforeRetry(backoffMillis);
            }
        }
        throw lastException == null ? new IllegalStateException("embedding 批次重试失败。") : lastException;
    }

    private List<VectorBatch> buildBatches(List<SuperAgentDocumentChunk> validChunkList, int batchSize) {
        List<VectorBatch> batches = new ArrayList<>();
        for (int startIndex = 0; startIndex < validChunkList.size(); startIndex += batchSize) {
            int endIndex = Math.min(startIndex + batchSize, validChunkList.size());
            batches.add(new VectorBatch(
                (startIndex / batchSize) + 1,
                startIndex + 1,
                endIndex,
                validChunkList.subList(startIndex, endIndex)
            ));
        }
        return batches;
    }

    @Override
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }

        try {
            String deleteSql = DELETE_BY_TASK_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.EMBEDDING_TABLE_NAME);
            pgVectorOperations.update(vectorTenant(documentId), deleteSql, documentId, taskId);
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_VECTOR_FAILED.getCode(),
                "删除 PGVector 任务数据失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }

        try {
            String deleteSql = DELETE_BY_DOCUMENT_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.EMBEDDING_TABLE_NAME);
            pgVectorOperations.update(vectorTenant(documentId), deleteSql, documentId);
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_VECTOR_FAILED.getCode(),
                "删除 PGVector 数据失败: " + exception.getMessage(), exception);
        }
    }

    private void batchUpsert(String upsertSql,
                             List<SuperAgentDocumentChunk> chunkBatch,
                             List<float[]> embeddingBatch,
                             String embeddingModelName,
                             Long vectorTenantId) {
        pgVectorOperations.batchUpdate(vectorTenantId, upsertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                SuperAgentDocumentChunk chunk = chunkBatch.get(index);
                float[] embedding = embeddingBatch.get(index);

                chunk.setVectorStatus(DocumentVectorStatusEnum.VECTORIZING.getCode());
                String metadataJson = buildMetadataJson(chunk, embeddingModelName);

                ps.setLong(1, chunk.getId());
                ps.setLong(2, chunk.getDocumentId());
                ps.setLong(3, chunk.getTaskId());
                if (chunk.getPlanId() == null) {
                    ps.setNull(4, Types.BIGINT);
                }
                else {
                    ps.setLong(4, chunk.getPlanId());
                }
                if (chunk.getParentBlockId() == null) {
                    ps.setNull(5, Types.BIGINT);
                }
                else {
                    ps.setLong(5, chunk.getParentBlockId());
                }
                ps.setInt(6, chunk.getChunkNo());
                ps.setInt(7, defaultInteger(chunk.getSourceType()));
                ps.setString(8, chunk.getSectionPath());
                if (chunk.getStructureNodeId() == null) {
                    ps.setNull(9, Types.BIGINT);
                }
                else {
                    ps.setLong(9, chunk.getStructureNodeId());
                }
                ps.setInt(10, defaultInteger(chunk.getStructureNodeType()));
                ps.setString(11, chunk.getCanonicalPath());
                ps.setInt(12, defaultInteger(chunk.getItemIndex()));
                ps.setString(13, chunk.getChunkText());
                ps.setString(14, embeddingInput(chunk));
                ps.setString(15, chunk.getChunkType());
                ps.setString(16, chunk.getTitle());
                ps.setString(17, chunk.getKeywords());
                ps.setString(18, chunk.getQuestions());
                ps.setInt(19, defaultInteger(chunk.getCharCount()));
                ps.setInt(20, defaultInteger(chunk.getTokenCount()));
                if (chunk.getPageNo() == null) {
                    ps.setNull(21, Types.INTEGER);
                }
                else {
                    ps.setInt(21, chunk.getPageNo());
                }
                ps.setString(22, chunk.getPageRange());
                ps.setString(23, chunk.getBboxJson());
                ps.setString(24, chunk.getSourceBlockIds());
                ps.setString(25, embeddingModelName);
                ps.setString(26, metadataJson);

                ps.setString(27, toVectorLiteral(embedding));
                ps.setInt(28, 1);
                ps.setLong(29, vectorTenantId);
            }

            @Override
            public int getBatchSize() {
                return chunkBatch.size();
            }
        });
    }

    /**
     * 向量数据所属租户（来自父文档）。
     *
     * <p>解析不到就拒绝写入：向量行带不出正确租户时，RLS 的 WITH CHECK 会拒绝它，
     * 而在那之前这里先给出明确原因（文档不存在 / 租户缺失），避免把配置问题伪装成数据库错误。</p>
     */
    private Long vectorTenant(Long documentId) {
        Long tenantId = documentTenantLookup.tenantOfDocument(documentId);
        if (tenantId == null) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_VECTOR_FAILED.getCode(),
                "无法确定文档所属租户，拒绝写入向量数据: " + documentId);
        }
        return tenantId;
    }

    private void markSuccess(List<SuperAgentDocumentChunk> chunkBatch) {
        for (SuperAgentDocumentChunk chunk : chunkBatch) {

            chunk.setVectorId(String.valueOf(chunk.getId()));
            chunk.setVectorStoreType(DocumentVectorStoreTypeEnum.PG_VECTOR.getCode());
            chunk.setVectorStatus(DocumentVectorStatusEnum.VECTOR_SUCCESS.getCode());
        }
    }

    private String buildMetadataJson(SuperAgentDocumentChunk chunk, String embeddingModelName) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("taskId", chunk.getTaskId());
        metadata.put("planId", chunk.getPlanId());
        metadata.put("parentBlockId", chunk.getParentBlockId());
        metadata.put("chunkNo", chunk.getChunkNo());
        metadata.put("sourceType", chunk.getSourceType());
        metadata.put("sectionPath", chunk.getSectionPath());
        metadata.put("structureNodeId", chunk.getStructureNodeId());
        metadata.put("structureNodeType", chunk.getStructureNodeType());
        metadata.put("canonicalPath", chunk.getCanonicalPath());
        metadata.put("itemIndex", chunk.getItemIndex());
        metadata.put("contentWithWeight", embeddingInput(chunk));
        metadata.put("chunkType", chunk.getChunkType());
        metadata.put("title", chunk.getTitle());
        metadata.put("keywords", chunk.getKeywords());
        metadata.put("questions", chunk.getQuestions());
        metadata.put("charCount", chunk.getCharCount());
        metadata.put("tokenCount", chunk.getTokenCount());
        metadata.put("pageNo", chunk.getPageNo());
        metadata.put("pageRange", chunk.getPageRange());
        metadata.put("bboxJson", chunk.getBboxJson());
        metadata.put("sourceBlockIds", chunk.getSourceBlockIds());
        metadata.put("embeddingModel", embeddingModelName);
        try {
            return objectMapper.writeValueAsString(metadata);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 PGVector metadata 失败。", exception);
        }
    }

    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("EmbeddingPort 返回了空向量。");
        }
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {

            if (index > 0) {
                vectorBuilder.append(",");
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append("]");
        return vectorBuilder.toString();
    }

    private EmbeddingPort requireEmbeddingModel() {

        EmbeddingPort embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("当前未找到可用的 EmbeddingPort，无法执行向量化。");
        }
        return embeddingModel;
    }

    private String resolveEmbeddingModelName() {
        return requireEmbeddingModel().model();
    }

    private int embeddingBatchSize() {
        Integer configured = embeddingDefaults().batchSize();
        if (configured == null || configured <= 0) {
            return EMBEDDING_BATCH_SIZE_LIMIT;
        }
        return Math.min(configured, EMBEDDING_BATCH_SIZE_LIMIT);
    }

    private int embeddingBatchMaxAttempts() {
        Integer configured = embeddingDefaults().maxAttempts();
        if (configured == null || configured <= 1) {
            return 1;
        }
        return Math.min(configured, 5);
    }

    private long embeddingRetryBackoffMillis(int attempt) {
        Long configured = embeddingDefaults().retryBackoffMillis();
        long baseMillis = configured == null || configured <= 0L ? 1200L : configured;
        long multiplier = Math.max(1L, attempt);
        return Math.min(10000L, baseMillis * multiplier);
    }

    private int embeddingParallelism(int totalBatchCount) {
        Integer configured = embeddingDefaults().parallelism();
        if (configured == null || configured <= 1 || totalBatchCount <= 1) {
            return 1;
        }
        return Math.min(Math.min(configured, 4), totalBatchCount);
    }

    private DocumentVectorConfigurationPort.EmbeddingDefaults embeddingDefaults() {
        DocumentVectorConfigurationPort.EmbeddingDefaults defaults = vectorConfiguration == null
            ? null
            : vectorConfiguration.currentEmbeddingDefaults();
        return defaults == null
            ? DocumentVectorConfigurationPort.EmbeddingDefaults.defaults()
            : defaults;
    }

    private boolean isTransientEmbeddingException(Throwable failure) {
        return !Thread.currentThread().isInterrupted()
            && failure instanceof org.smartledge.ai.rag.runtime.model.ModelCallException modelFailure
            && modelFailure.retryable();
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable cursor = throwable;
        Throwable root = throwable;
        while (cursor != null) {
            root = cursor;
            cursor = cursor.getCause();
        }
        return root == null ? "" : StrUtil.maxLength(StrUtil.blankToDefault(root.getMessage(), root.getClass().getSimpleName()), 240);
    }

    private void sleepBeforeRetry(long backoffMillis) {
        try {
            Thread.sleep(backoffMillis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding 批次重试等待被中断。", exception);
        }
    }

    private ThreadFactory vectorThreadFactory() {
        AtomicInteger index = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "document-vector-batch-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private String embeddingInput(SuperAgentDocumentChunk chunk) {
        if (chunk == null) {
            return "";
        }
        return StrUtil.blankToDefault(chunk.getContentWithWeight(), chunk.getChunkText()).trim();
    }

    private int defaultInteger(Integer value) {

        return Objects.requireNonNullElse(value, 0);
    }

    private record VectorBatch(
        int batchIndex,
        int startNo,
        int endNo,
        List<SuperAgentDocumentChunk> chunks
    ) {
    }
}
