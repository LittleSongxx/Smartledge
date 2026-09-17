package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentRaptorNode;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentRaptorNodeMapper;
import org.smartledge.ai.knowledge.augmentation.model.raptor.RaptorSearchResult;
import org.smartledge.ai.knowledge.augmentation.service.RaptorSearchService;
import org.smartledge.ai.knowledge.augmentation.service.RaptorSummaryIndexService;
import org.smartledge.ai.manage.support.DocumentPgVectorConstants;
import org.smartledge.ai.manage.support.PgVectorTenantOperations;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.ai.knowledge.augmentation.support.RaptorScopeSupport;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentChunkSourceTypeEnum;
import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class RaptorSearchServiceImpl implements RaptorSearchService {

    private static final String SOURCE_STATUS_SOURCE_CHUNK = "SOURCE_CHUNK";

    private static final String SOURCE_STATUS_SOURCE_PARENT_BLOCK = "SOURCE_PARENT_BLOCK";

    private static final String SOURCE_STATUS_SUMMARY_ONLY = "SUMMARY_ONLY";

    /** 与文档向量检索保持同一 over-fetch 口径（原因见 PgVectorTenantOperations 的说明）。 */
    private static final int TENANT_OVER_FETCH_FACTOR = 4;

    private static final String RAPTOR_RETRIEVE_SQL_TEMPLATE = """
        SELECT
            id,
            document_id,
            task_id,
            scope_type,
            scope_key,
            node_level,
            node_no,
            title,
            summary,
            summary_with_weight,
            source_chunk_ids_json,
            source_parent_block_ids_json,
            section_path,
            page_range,
            keywords,
            questions,
            1 - (embedding <=> CAST(? AS vector)) AS similarity_score
        FROM %s
        WHERE status = 1
          AND tenant_id = ?
          AND (
            (document_id IN (%s) AND task_id IN (%s))
            %s
          )
        ORDER BY embedding <=> CAST(? AS vector)
        LIMIT ?
        """;

    @Qualifier("documentManagePgVectorJdbcTemplate")
    private final JdbcTemplate pgVectorJdbcTemplate;

    private final PgVectorTenantOperations pgVectorOperations;

    private final ObjectProvider<EmbeddingPort> embeddingModelProvider;

    private final SuperAgentRaptorNodeMapper raptorNodeMapper;

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final ObjectMapper objectMapper;

    private final ObjectProvider<RaptorSummaryIndexService> raptorSummaryIndexServiceProvider;

    public RaptorSearchServiceImpl(
        @Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
        PgVectorTenantOperations pgVectorOperations,
        ObjectProvider<EmbeddingPort> embeddingModelProvider,
        SuperAgentRaptorNodeMapper raptorNodeMapper,
        SuperAgentDocumentMapper documentMapper,
        SuperAgentDocumentChunkMapper chunkMapper,
        ObjectMapper objectMapper,
        ObjectProvider<RaptorSummaryIndexService> raptorSummaryIndexServiceProvider) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.pgVectorOperations = pgVectorOperations;
        this.embeddingModelProvider = embeddingModelProvider;
        this.raptorNodeMapper = raptorNodeMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.objectMapper = objectMapper;
        this.raptorSummaryIndexServiceProvider = raptorSummaryIndexServiceProvider;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RaptorSearchResult> search(String question,
                                           List<Long> documentIds,
                                           List<Long> taskIds,
                                           int topK,
                                           int sourceChunkTopK) {
        if (StrUtil.isBlank(question) || CollUtil.isEmpty(documentIds) || CollUtil.isEmpty(taskIds) || topK <= 0) {
            return List.of();
        }

        List<SuperAgentDocument> scopeDocuments = loadScopeDocuments(documentIds);
        List<String> datasetScopeKeys = RaptorScopeSupport.searchScopeKeys(scopeDocuments);
        List<RaptorNodeHit> nodeHits = retrieveSummaryNodes(question, documentIds, taskIds, datasetScopeKeys, Math.max(topK * 3, topK));
        if (nodeHits.isEmpty()) {
            return List.of();
        }

        Map<Long, SuperAgentRaptorNode> nodeMap = loadNodes(nodeHits);
        Set<Long> allowedDocumentIds = new LinkedHashSet<>(documentIds);
        Set<Long> allowedTaskIds = new LinkedHashSet<>(taskIds);
        Map<String, RaptorSearchResult> resultMap = new LinkedHashMap<>();
        for (RaptorNodeHit hit : nodeHits) {
            SuperAgentRaptorNode node = nodeMap.get(hit.nodeId());
            if (node == null) {
                continue;
            }
            List<SuperAgentDocumentChunk> chunks = loadSourceChunks(node, sourceChunkTopK, allowedDocumentIds, allowedTaskIds);
            if (chunks.isEmpty()) {
                RaptorSearchResult summaryOnly = toSummaryOnlyResult(node, hit.score(), allowedDocumentIds, allowedTaskIds);
                if (summaryOnly != null) {
                    resultMap.merge(resultKey(summaryOnly), summaryOnly,
                        (left, right) -> left.getScore() >= right.getScore() ? left : right);
                }
                continue;
            }
            boolean summaryVisible = summaryScopeFullyVisible(node, allowedDocumentIds);
            for (SuperAgentDocumentChunk chunk : chunks) {
                if (chunk == null || chunk.getId() == null) {
                    continue;
                }
                RaptorSearchResult result = toResult(node, chunk, hit.score(), 0D, summaryVisible);
                resultMap.merge(resultKey(result), result,
                    (left, right) -> left.getScore() >= right.getScore() ? left : right);
            }
        }

        return resultMap.values().stream()
            .sorted(Comparator.comparingDouble(RaptorSearchResult::getScore).reversed())
            .limit(topK)
            .toList();
    }

    private List<RaptorNodeHit> retrieveSummaryNodes(String question,
                                                     List<Long> documentIds,
                                                     List<Long> taskIds,
                                                     List<String> datasetScopeKeys,
                                                     int topK) {
        Map<Long, RaptorNodeHit> mergedHits = new LinkedHashMap<>();
        List<RaptorNodeHit> vectorHits = retrieveVectorSummaryNodes(question, documentIds, taskIds, datasetScopeKeys, topK);
        mergeHits(mergedHits, vectorHits, 1.0D);
        RaptorSummaryIndexService indexService = raptorSummaryIndexServiceProvider.getIfAvailable();
        if (indexService != null) {
            List<RaptorSummaryIndexService.RaptorSummaryHit> lexicalHits = indexService.search(question, documentIds, taskIds, datasetScopeKeys, topK);
            double maxLexicalScore = lexicalHits.stream()
                .mapToDouble(RaptorSummaryIndexService.RaptorSummaryHit::score)
                .max()
                .orElse(0D);
            List<RaptorNodeHit> normalizedLexicalHits = lexicalHits.stream()
                .map(hit -> new RaptorNodeHit(hit.nodeId(), normalizeLexicalScore(hit.score(), maxLexicalScore)))
                .toList();
            mergeHits(mergedHits, normalizedLexicalHits, 0.85D);
        }
        return mergedHits.values().stream()
            .sorted(Comparator.comparingDouble(RaptorNodeHit::score).reversed())
            .limit(Math.max(1, Math.min(topK, 50)))
            .toList();
    }

    private List<RaptorNodeHit> retrieveVectorSummaryNodes(String question,
                                                           List<Long> documentIds,
                                                           List<Long> taskIds,
                                                           List<String> datasetScopeKeys,
                                                           int topK) {
        EmbeddingPort embeddingModel = requireEmbeddingModel();
        String questionVector = toVectorLiteral(embeddingModel.embed(question.trim()));
        String datasetScopeClause = CollUtil.isEmpty(datasetScopeKeys)
            ? ""
            : " OR (scope_type = ? AND scope_key IN (" + buildPlaceholders(datasetScopeKeys.size()) + "))";
        String sql = RAPTOR_RETRIEVE_SQL_TEMPLATE.formatted(
            DocumentPgVectorConstants.RAPTOR_EMBEDDING_TABLE_NAME,
            buildPlaceholders(documentIds.size()),
            buildPlaceholders(taskIds.size()),
            datasetScopeClause
        );
        Long tenantId = TenantContext.get();
        int effectiveTopK = Math.max(1, Math.min(topK, 50));
        List<Object> params = new ArrayList<>();
        params.add(questionVector);
        params.add(tenantId);
        params.addAll(documentIds);
        params.addAll(taskIds);
        if (CollUtil.isNotEmpty(datasetScopeKeys)) {
            params.add(RaptorScopeSupport.SCOPE_TYPE_DATASET);
            params.addAll(datasetScopeKeys);
        }
        params.add(questionVector);
        // 与文档向量检索同一口径：ANN 不参与 RLS 过滤，因此多取再截回，避免被策略过滤后召回不足。
        params.add(effectiveTopK * TENANT_OVER_FETCH_FACTOR);

        List<RaptorNodeHit> hits = pgVectorOperations.query(tenantId, sql, (resultSet, rowNum) -> new RaptorNodeHit(
            resultSet.getLong("id"),
            resultSet.getDouble("similarity_score")
        ), params.toArray());
        return hits.size() <= effectiveTopK ? hits : List.copyOf(hits.subList(0, effectiveTopK));
    }

    private void mergeHits(Map<Long, RaptorNodeHit> target, List<RaptorNodeHit> source, double weight) {
        if (source == null) {
            return;
        }
        for (RaptorNodeHit hit : source) {
            if (hit == null || hit.nodeId() == null) {
                continue;
            }
            double weightedScore = hit.score() * weight;
            target.merge(hit.nodeId(), new RaptorNodeHit(hit.nodeId(), weightedScore),
                (left, right) -> new RaptorNodeHit(left.nodeId(), Math.max(left.score(), right.score()) + Math.min(left.score(), right.score()) * 0.15D));
        }
    }

    private double normalizeLexicalScore(double score, double maxScore) {
        if (score <= 0D || maxScore <= 0D) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, score / maxScore));
    }

    private Map<Long, SuperAgentRaptorNode> loadNodes(List<RaptorNodeHit> nodeHits) {
        List<Long> nodeIds = nodeHits.stream()
            .map(RaptorNodeHit::nodeId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        return raptorNodeMapper.selectBatchIds(nodeIds).stream()
            .collect(Collectors.toMap(
                SuperAgentRaptorNode::getId,
                node -> node,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private List<SuperAgentDocumentChunk> loadSourceChunks(SuperAgentRaptorNode node,
                                                           int sourceChunkTopK,
                                                           Set<Long> allowedDocumentIds,
                                                           Set<Long> allowedTaskIds) {
        List<Long> chunkIds = readLongList(node.getSourceChunkIdsJson());
        List<SuperAgentDocumentChunk> chunks = List.of();
        if (!chunkIds.isEmpty()) {
            chunks = chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .in(SuperAgentDocumentChunk::getId, chunkIds)
                .in(SuperAgentDocumentChunk::getDocumentId, allowedDocumentIds)
                .in(SuperAgentDocumentChunk::getTaskId, allowedTaskIds)
                .eq(SuperAgentDocumentChunk::getSourceType, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode()));
        }
        Map<Long, Integer> orderMap = new LinkedHashMap<>();
        for (int index = 0; index < chunkIds.size(); index++) {
            orderMap.put(chunkIds.get(index), index);
        }
        List<SuperAgentDocumentChunk> resolvedChunks = chunks.stream()
            .filter(chunk -> allowedDocumentIds.contains(chunk.getDocumentId()))
            .filter(chunk -> allowedTaskIds.contains(chunk.getTaskId()))
            .filter(chunk -> Objects.equals(chunk.getStatus(), BusinessStatus.YES.getCode()))
            .filter(chunk -> Objects.equals(chunk.getSourceType(), DocumentChunkSourceTypeEnum.ORIGINAL.getCode()))
            .filter(chunk -> StrUtil.isNotBlank(chunk.getChunkText()))
            .sorted(Comparator.comparingInt(chunk -> orderMap.getOrDefault(chunk.getId(), Integer.MAX_VALUE)))
            .limit(Math.max(1, sourceChunkTopK))
            .toList();
        if (!resolvedChunks.isEmpty()) {
            return resolvedChunks;
        }

        List<Long> parentBlockIds = readLongList(node.getSourceParentBlockIdsJson()).stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (parentBlockIds.isEmpty()) {
            return List.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .in(SuperAgentDocumentChunk::getParentBlockId, parentBlockIds)
                .in(SuperAgentDocumentChunk::getDocumentId, allowedDocumentIds)
                .in(SuperAgentDocumentChunk::getTaskId, allowedTaskIds)
                .eq(SuperAgentDocumentChunk::getSourceType, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId))
            .stream()
            .filter(chunk -> allowedDocumentIds.contains(chunk.getDocumentId()))
            .filter(chunk -> allowedTaskIds.contains(chunk.getTaskId()))
            .filter(chunk -> Objects.equals(chunk.getStatus(), BusinessStatus.YES.getCode()))
            .filter(chunk -> Objects.equals(chunk.getSourceType(), DocumentChunkSourceTypeEnum.ORIGINAL.getCode()))
            .filter(chunk -> StrUtil.isNotBlank(chunk.getChunkText()))
            .limit(Math.max(1, sourceChunkTopK))
            .toList();
    }

    private List<SuperAgentDocument> loadScopeDocuments(List<Long> documentIds) {
        if (CollUtil.isEmpty(documentIds)) {
            return List.of();
        }
        return documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .in(SuperAgentDocument::getId, documentIds)
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode()));
    }

    /**
     * 构建"源 chunk 命中"的结果。
     *
     * <p>{@code summaryVisible} 表示节点摘要是否落在可见范围内（见
     * {@link #summaryScopeFullyVisible}）。chunk 本身来自可见文档，因此始终可以作为证据；
     * 但节点摘要与节点标题是**对该节点全部来源文档聚合**生成的整段文字，事后无法按来源裁剪，
     * 所以可见范围不覆盖全部来源时不得携带它们 —— 只返回可见 chunk 原文，不返回聚合摘要。</p>
     */
    RaptorSearchResult toResult(SuperAgentRaptorNode node,
                                SuperAgentDocumentChunk chunk,
                                double score,
                                double rankFeatureBoost,
                                boolean summaryVisible) {
        return RaptorSearchResult.builder()
            .documentId(chunk.getDocumentId())
            .taskId(chunk.getTaskId())
            .raptorNodeId(node.getId())
            .raptorNodeTitle(summaryVisible ? node.getTitle() : null)
            .raptorNodeLevel(node.getNodeLevel())
            .raptorSummary(summaryVisible ? node.getSummary() : null)
            .sourceStatus(SOURCE_STATUS_SOURCE_CHUNK)
            .chunkId(chunk.getId())
            .parentBlockId(chunk.getParentBlockId())
            .chunkNo(chunk.getChunkNo())
            .chunkText(chunk.getChunkText())
            .title(chunk.getTitle())
            .sectionPath(StrUtil.blankToDefault(chunk.getSectionPath(), node.getSectionPath()))
            .pageNo(chunk.getPageNo())
            .pageRange(StrUtil.blankToDefault(chunk.getPageRange(), node.getPageRange()))
            .bboxJson(chunk.getBboxJson())
            .sourceBlockIds(chunk.getSourceBlockIds())
            .score(score)
            .rankFeatureBoost(rankFeatureBoost)
            .build();
    }

    /**
     * 仅摘要回退：节点没有落在可见范围内的源 chunk 时，返回节点自身摘要。
     *
     * <p>摘要文本是入库时对**该节点全部来源文档**聚合生成的整段文字，事后无法按来源裁剪。
     * 因此跨文档节点（dataset 级汇总）只有在调用方可见集合**覆盖其全部来源文档**时才允许返回：
     * 之前用 any 语义（{@code findFirst()}）判断，只要有一个来源文档可见就返回整段摘要，
     * 会把范围外文档的内容带进结果，违反 hard scope 不变量。</p>
     *
     * <p>两个维度的语义刻意不同，不要合并成同一种判定：
     * 文档维度是**权限边界**，必须全部来源可见（ALL）；任务维度是**新鲜度**判定，
     * 只要有一个来源索引版本仍在当前可见版本集合内即可（ANY），因为同一份文档可以被重建索引多次，
     * 要求全部任务可见会让所有跨版本的汇总节点失效。</p>
     *
     * <p>包级可见而非 private，便于同包测试直接锁定该可见性判定。</p>
     */
    RaptorSearchResult toSummaryOnlyResult(SuperAgentRaptorNode node,
                                                   double score,
                                                   Set<Long> allowedDocumentIds,
                                                   Set<Long> allowedTaskIds) {
        if (!summaryScopeFullyVisible(node, allowedDocumentIds)) {
            return null;
        }
        Long documentId = firstAllowedId(node.getDocumentId(), node.getSourceDocumentIdsJson(), allowedDocumentIds);
        Long taskId = firstAllowedId(node.getTaskId(), node.getSourceTaskIdsJson(), allowedTaskIds);
        if (documentId == null || taskId == null) {
            return null;
        }
        Long parentBlockId = readLongList(node.getSourceParentBlockIdsJson()).stream()
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        String sourceStatus = parentBlockId == null ? SOURCE_STATUS_SUMMARY_ONLY : SOURCE_STATUS_SOURCE_PARENT_BLOCK;
        return RaptorSearchResult.builder()
            .documentId(documentId)
            .taskId(taskId)
            .raptorNodeId(node.getId())
            .raptorNodeTitle(node.getTitle())
            .raptorNodeLevel(node.getNodeLevel())
            .raptorSummary(node.getSummary())
            .sourceStatus(sourceStatus)
            .parentBlockId(parentBlockId)
            .title(node.getTitle())
            .sectionPath(node.getSectionPath())
            .pageRange(node.getPageRange())
            .sourceBlockIds(joinLongList(readLongList(node.getSourceParentBlockIdsJson())))
            .score(score)
            .build();
    }

    /**
     * 节点摘要是否落在可见范围内（文档维度 ALL 语义）。
     *
     * <p>这是摘要类内容**唯一**的可见性判定：仅摘要回退与"源 chunk 命中但仍携带摘要"两条路径
     * 都走这里，避免出现一条路径做了全部来源校验、另一条漏掉的情况。</p>
     *
     * <p>没有来源文档记录的节点（{@code sourceDocumentIdsJson} 为空）视为"摘要不是聚合产物"，
     * 由调用方的 documentId 主键校验继续约束。</p>
     */
    boolean summaryScopeFullyVisible(SuperAgentRaptorNode node, Set<Long> allowedDocumentIds) {
        List<Long> sourceDocumentIds = readLongList(node.getSourceDocumentIdsJson());
        return sourceDocumentIds.isEmpty() || allowedDocumentIds.containsAll(sourceDocumentIds);
    }

    private Long firstAllowedId(Long primaryId, String sourceIdsJson, Set<Long> allowedIds) {
        if (primaryId != null && allowedIds.contains(primaryId)) {
            return primaryId;
        }
        return readLongList(sourceIdsJson).stream()
            .filter(allowedIds::contains)
            .findFirst()
            .orElse(null);
    }

    private String resultKey(RaptorSearchResult result) {
        if (result.getChunkId() != null) {
            return "chunk:" + result.getChunkId();
        }
        if (result.getParentBlockId() != null) {
            return "parent:" + result.getRaptorNodeId() + ":" + result.getParentBlockId();
        }
        return "summary:" + result.getRaptorNodeId() + ":" + result.getDocumentId() + ":" + result.getTaskId();
    }

    private String joinLongList(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
    }

    private EmbeddingPort requireEmbeddingModel() {
        EmbeddingPort embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("当前未找到可用的向量化模型，无法执行 RAPTOR 摘要检索。");
        }
        return embeddingModel;
    }

    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("问题向量生成失败，无法执行 RAPTOR 检索。");
        }
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                vectorBuilder.append(',');
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append(']');
        return vectorBuilder.toString();
    }

    private List<Long> readLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        }
        catch (Exception exception) {
            return List.of();
        }
    }

    private String buildPlaceholders(int size) {
        return IntStream.range(0, size)
            .mapToObj(index -> "?")
            .collect(Collectors.joining(","));
    }

    private record RaptorNodeHit(Long nodeId, double score) {
    }
}
