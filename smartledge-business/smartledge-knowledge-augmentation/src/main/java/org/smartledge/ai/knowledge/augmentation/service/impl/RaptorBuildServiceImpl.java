package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeTopicNode;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentRaptorNode;
import org.smartledge.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentRaptorNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.smartledge.ai.manage.model.KnowledgeBaseIndexingOptions;
import org.smartledge.ai.knowledge.augmentation.model.raptor.RaptorBuildResult;
import org.smartledge.ai.knowledge.augmentation.model.raptor.RaptorQualityReport;
import org.smartledge.ai.knowledge.augmentation.service.RaptorBuildService;
import org.smartledge.ai.knowledge.augmentation.service.RaptorQualityService;
import org.smartledge.ai.knowledge.augmentation.service.RaptorSummaryIndexService;
import org.smartledge.ai.manage.support.DocumentPgVectorConstants;
import org.smartledge.enums.DocumentManageCode;
import org.smartledge.exception.SuperAgentFrameException;
import org.smartledge.ai.manage.support.DocumentTenantLookup;
import org.smartledge.ai.manage.support.PgVectorTenantOperations;
import org.smartledge.ai.manage.support.MybatisBatchExecutor;
import org.smartledge.ai.knowledge.augmentation.support.RaptorDatasetBuildSupport;
import org.smartledge.ai.knowledge.augmentation.support.RaptorScopeSupport;
import org.smartledge.ai.knowledge.augmentation.port.RaptorBuildPort;
import org.smartledge.ai.knowledge.augmentation.port.RaptorBuildConfigurationPort;
import org.smartledge.ai.knowledge.augmentation.port.KnowledgeBaseAugmentationConfigurationPort;
import org.smartledge.ai.knowledge.augmentation.model.RaptorBuildRequest;
import org.smartledge.ai.knowledge.augmentation.model.RaptorBuildResponse;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentChunkSourceTypeEnum;
import org.smartledge.enums.DocumentIndexStatusEnum;
import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RaptorBuildServiceImpl implements RaptorBuildService {

    private static final String UPSERT_SQL_TEMPLATE = """
        INSERT INTO %s
        (id, document_id, task_id, scope_type, scope_key, node_level, node_no, parent_node_id, title, summary, summary_with_weight,
         source_chunk_ids_json, source_parent_block_ids_json, source_document_ids_json, source_task_ids_json, section_path, page_range, keywords, questions,
         embedding_model, metadata_json, embedding, create_time, edit_time, status, tenant_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS vector), NOW(), NOW(), ?, ?)
        ON CONFLICT (id) DO UPDATE SET
            document_id = EXCLUDED.document_id,
            task_id = EXCLUDED.task_id,
            scope_type = EXCLUDED.scope_type,
            scope_key = EXCLUDED.scope_key,
            node_level = EXCLUDED.node_level,
            node_no = EXCLUDED.node_no,
            parent_node_id = EXCLUDED.parent_node_id,
            title = EXCLUDED.title,
            summary = EXCLUDED.summary,
            summary_with_weight = EXCLUDED.summary_with_weight,
            source_chunk_ids_json = EXCLUDED.source_chunk_ids_json,
            source_parent_block_ids_json = EXCLUDED.source_parent_block_ids_json,
            source_document_ids_json = EXCLUDED.source_document_ids_json,
            source_task_ids_json = EXCLUDED.source_task_ids_json,
            section_path = EXCLUDED.section_path,
            page_range = EXCLUDED.page_range,
            keywords = EXCLUDED.keywords,
            questions = EXCLUDED.questions,
            embedding_model = EXCLUDED.embedding_model,
            metadata_json = EXCLUDED.metadata_json,
            embedding = EXCLUDED.embedding,
            edit_time = NOW(),
            status = EXCLUDED.status,
            tenant_id = EXCLUDED.tenant_id
        """;

    private static final String DELETE_VECTOR_BY_TASK_SQL_TEMPLATE = "DELETE FROM %s WHERE document_id = ? AND task_id = ?";

    private static final String DELETE_VECTOR_BY_DOCUMENT_SQL_TEMPLATE = "DELETE FROM %s WHERE document_id = ?";

    private static final String DELETE_VECTOR_BY_SCOPE_SQL_TEMPLATE = "DELETE FROM %s WHERE scope_type = ? AND scope_key = ?";

    private static final long DATASET_DOCUMENT_ID = 0L;

    private static final long DATASET_TASK_ID = 0L;

    private static final int EMBEDDING_BATCH_SIZE_LIMIT = 10;

    private final SuperAgentRaptorNodeMapper raptorNodeMapper;

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final SuperAgentKnowledgeTopicNodeMapper topicNodeMapper;

    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;

    private final RaptorBuildPort raptorBuildPort;

    private final ObjectMapper objectMapper;

    private final UidGenerator uidGenerator;

    @Qualifier("documentManagePgVectorJdbcTemplate")
    private final JdbcTemplate pgVectorJdbcTemplate;

    private final PgVectorTenantOperations pgVectorOperations;

    private final DocumentTenantLookup documentTenantLookup;

    private final ObjectProvider<EmbeddingPort> embeddingModelProvider;

    @Autowired(required = false)
    private RaptorBuildConfigurationPort configurationPort;

    private final RaptorQualityService raptorQualityService;

    private final RaptorDatasetBuildSupport raptorDatasetBuildSupport;

    private final ObjectProvider<RaptorSummaryIndexService> raptorSummaryIndexServiceProvider;

    private final KnowledgeBaseAugmentationConfigurationPort indexingConfigResolver;

    /**
     * RAPTOR 构建的事务边界。
     *
     * <p>本类不得在 {@code @Transactional} 方法内调用 Python 构建接口或模型向量化接口：
     * 那会把数据库连接与行锁持有到外部调用返回，长耗时甚至数分钟。
     * 因此所有数据库写入都通过本模板收敛为短事务，外部调用一律在事务之外。</p>
     */
    private final TransactionTemplate transactionTemplate;

    public RaptorBuildServiceImpl(
        SuperAgentRaptorNodeMapper raptorNodeMapper,
        SuperAgentDocumentMapper documentMapper,
        SuperAgentDocumentChunkMapper chunkMapper,
        SuperAgentKnowledgeTopicNodeMapper topicNodeMapper,
        SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper,
        RaptorBuildPort raptorBuildPort,
        ObjectMapper objectMapper,
        UidGenerator uidGenerator,
        @Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
        PgVectorTenantOperations pgVectorOperations,
        DocumentTenantLookup documentTenantLookup,
        ObjectProvider<EmbeddingPort> embeddingModelProvider,
        RaptorQualityService raptorQualityService,
        RaptorDatasetBuildSupport raptorDatasetBuildSupport,
        ObjectProvider<RaptorSummaryIndexService> raptorSummaryIndexServiceProvider,
        KnowledgeBaseAugmentationConfigurationPort indexingConfigResolver,
        PlatformTransactionManager transactionManager) {
        this.raptorNodeMapper = raptorNodeMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.topicNodeMapper = topicNodeMapper;
        this.topicDocumentRelationMapper = topicDocumentRelationMapper;
        this.raptorBuildPort = raptorBuildPort;
        this.objectMapper = objectMapper;
        this.uidGenerator = uidGenerator;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.pgVectorOperations = pgVectorOperations;
        this.documentTenantLookup = documentTenantLookup;
        this.embeddingModelProvider = embeddingModelProvider;
        this.raptorQualityService = raptorQualityService;
        this.raptorDatasetBuildSupport = raptorDatasetBuildSupport;
        this.raptorSummaryIndexServiceProvider = raptorSummaryIndexServiceProvider;
        this.indexingConfigResolver = indexingConfigResolver;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public RaptorBuildResult rebuildDocumentTree(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks) {
        KnowledgeBaseIndexingOptions.RaptorBuildOptions raptorOptions = raptorBuildOptionsByDocumentId(documentId);
        if (!Boolean.TRUE.equals(raptorOptions.getRaptorBuildEnabled())) {
            withdrawDocumentNodes(documentId, taskId);
            log.info("知识库配置已关闭 RAPTOR 构建，跳过文档摘要树构建: documentId={}, taskId={}", documentId, taskId);
            return RaptorBuildResult.builder().build();
        }
        if (documentId == null || taskId == null || CollUtil.isEmpty(chunks)) {
            return RaptorBuildResult.builder().build();
        }

        long buildStartedNanos = System.nanoTime();
        log.info("开始构建 RAPTOR 摘要树，documentId={}, taskId={}, chunkCount={}", documentId, taskId, chunks.size());
        String scopeKey = RaptorScopeSupport.documentScopeKey(documentId);
        long pythonStartedNanos = System.nanoTime();
        RaptorBuildResponse response = raptorBuildPort.build(buildRequest(documentId, taskId, chunks, raptorOptions));
        log.info("Python RAPTOR 构建调用完成，documentId={}, taskId={}, scopeType={}, scopeKey={}, costMillis={}",
            documentId, taskId, RaptorScopeSupport.SCOPE_TYPE_DOCUMENT, scopeKey, elapsedMillis(pythonStartedNanos));
        if (response == null) {
            throw new IllegalStateException("Python RAPTOR 构建接口返回为空。");
        }
        logLlmSummaryFallbacks(documentId, taskId, response.getNodes(), Boolean.TRUE.equals(raptorOptions.getRaptorLlmSummaryEnabled()));
        double qualityFloor = qualityFloor(raptorOptions);
        RaptorQualityReport sourceQualityReport = raptorQualityService.evaluatePythonNodes(response.getNodes(), qualityFloor);
        log.info("Python RAPTOR 原始摘要质量评测完成，documentId={}, taskId={}, nodeCount={}, avgQuality={}, minQuality={}, p10Quality={}, medianQuality={}, recommendedFloor={}, lowQualityCount={}",
            documentId,
            taskId,
            sourceQualityReport.getNodeCount(),
            sourceQualityReport.getAverageQualityScore(),
            sourceQualityReport.getMinQualityScore(),
            sourceQualityReport.getP10QualityScore(),
            sourceQualityReport.getMedianQualityScore(),
            sourceQualityReport.getRecommendedQualityFloor(),
            sourceQualityReport.getLowQualityNodeCount());

        Map<String, Long> idMap = allocateNodeIds(response.getNodes(), qualityFloor);
        List<SuperAgentRaptorNode> nodes = buildNodeEntities(
            documentId,
            taskId,
            RaptorScopeSupport.SCOPE_TYPE_DOCUMENT,
            scopeKey,
            response.getNodes(),
            idMap,
            distinctChunkDocumentIds(chunks),
            distinctChunkTaskIds(chunks),
            null,
            qualityFloor
        );
        if (CollUtil.isEmpty(nodes)) {
            withdrawDocumentNodes(documentId, taskId);
            log.info("RAPTOR 构建结果没有达到质量阈值的摘要节点，documentId={}, taskId={}, qualityFloor={}",
                documentId, taskId, qualityFloor);
            return RaptorBuildResult.builder()
                .sourceQualityReport(sourceQualityReport)
                .savedQualityReport(raptorQualityService.evaluatePythonNodes(List.of(), qualityFloor))
                .build();
        }
        long insertStartedNanos = System.nanoTime();
        persistDocumentNodes(documentId, taskId, nodes);
        log.info("RAPTOR 节点入库完成，documentId={}, taskId={}, nodeCount={}, costMillis={}",
            documentId, taskId, nodes.size(), elapsedMillis(insertStartedNanos));
        vectorize(nodes);
        indexSummaries(nodes);

        int levelCount = nodes.stream()
            .map(SuperAgentRaptorNode::getNodeLevel)
            .filter(Objects::nonNull)
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll)
            .size();
        int sourceChunkCount = nodes.stream()
            .flatMap(node -> readLongList(node.getSourceChunkIdsJson()).stream())
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll)
            .size();

        RaptorBuildResult result = RaptorBuildResult.builder()
            .nodeCount(nodes.size())
            .levelCount(levelCount)
            .sourceChunkCount(sourceChunkCount)
            .sourceQualityReport(sourceQualityReport)
            .savedQualityReport(raptorQualityService.evaluate(documentId, taskId))
            .build();
        log.info("RAPTOR 层级摘要树构建完成: documentId={}, taskId={}, result={}, costMillis={}",
            documentId, taskId, result, elapsedMillis(buildStartedNanos));
        return result;
    }

    @Override
    public RaptorBuildResult rebuildKnowledgeScopeTree(Long knowledgeBaseId, Long scopeId) {
        if (knowledgeBaseId == null || scopeId == null || scopeId <= 0) {
            return RaptorBuildResult.builder().build();
        }
        String scopeKey = RaptorScopeSupport.knowledgeScopeKey(knowledgeBaseId, scopeId);
        KnowledgeBaseIndexingOptions.RaptorBuildOptions raptorOptions = raptorBuildOptionsByKnowledgeBaseId(knowledgeBaseId);
        if (!Boolean.TRUE.equals(raptorOptions.getRaptorBuildEnabled())) {
            withdrawScopeNodes(RaptorScopeSupport.SCOPE_TYPE_DATASET, scopeKey);
            log.info("知识库配置已关闭 RAPTOR 构建，跳过 dataset-level 摘要树构建: knowledgeBaseId={}, scopeId={}, scopeKey={}",
                knowledgeBaseId, scopeId, scopeKey);
            return RaptorBuildResult.builder().build();
        }
        List<Long> topicIds = topicNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
                .eq(SuperAgentKnowledgeTopicNode::getKnowledgeBaseId, knowledgeBaseId)
                .eq(SuperAgentKnowledgeTopicNode::getScopeId, scopeId)
                .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .map(SuperAgentKnowledgeTopicNode::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (topicIds.isEmpty()) {
            withdrawScopeNodes(RaptorScopeSupport.SCOPE_TYPE_DATASET, scopeKey);
            log.info("跳过 RAPTOR dataset-level 构建，知识范围没有可用主题: scopeKey={}", scopeKey);
            return RaptorBuildResult.builder().build();
        }
        List<Long> documentIds = topicDocumentRelationMapper.selectList(new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
                .eq(SuperAgentTopicDocumentRelation::getKnowledgeBaseId, knowledgeBaseId)
                .in(SuperAgentTopicDocumentRelation::getTopicId, topicIds)
                .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .map(SuperAgentTopicDocumentRelation::getDocumentId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (documentIds.isEmpty()) {
            withdrawScopeNodes(RaptorScopeSupport.SCOPE_TYPE_DATASET, scopeKey);
            log.info("跳过 RAPTOR dataset-level 构建，知识范围没有主题文档关联: scopeKey={}, topicCount={}",
                scopeKey, topicIds.size());
            return RaptorBuildResult.builder().build();
        }
        List<SuperAgentDocument> documents = documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .in(SuperAgentDocument::getId, documentIds)
            .eq(SuperAgentDocument::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .orderByAsc(SuperAgentDocument::getId));
        if (documents.size() < 2) {
            withdrawScopeNodes(RaptorScopeSupport.SCOPE_TYPE_DATASET, scopeKey);
            log.info("跳过 RAPTOR dataset-level 构建，知识域内已完成索引文档不足 2 篇: scopeKey={}, documentCount={}",
                scopeKey, documents.size());
            return RaptorBuildResult.builder().build();
        }
        List<SuperAgentDocumentChunk> chunks = loadLatestChunks(documents);
        List<Long> sourceDocumentIds = documents.stream()
            .map(SuperAgentDocument::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        List<Long> sourceTaskIds = documents.stream()
            .map(SuperAgentDocument::getLastIndexTaskId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        List<SuperAgentRaptorNode> reusableSummaryNodes = loadReusableDocumentSummaryNodes(documents);
        if (!coversAllDocuments(reusableSummaryNodes, sourceDocumentIds)) {
            reusableSummaryNodes = List.of();
        }
        RaptorDatasetBuildSupport.DatasetInputs datasetInputs =
            raptorDatasetBuildSupport.buildInputs(reusableSummaryNodes, chunks);
        if (CollUtil.isEmpty(datasetInputs.inputs())) {
            withdrawScopeNodes(RaptorScopeSupport.SCOPE_TYPE_DATASET, scopeKey);
            log.info("跳过 RAPTOR dataset-level 构建，知识域内没有可用输入: scopeKey={}, documentCount={}, originalChunkCount={}, reusableSummaryCount={}",
                scopeKey, documents.size(), chunks.size(), reusableSummaryNodes.size());
            return RaptorBuildResult.builder().build();
        }
        if (CollUtil.isEmpty(chunks)) {
            withdrawScopeNodes(RaptorScopeSupport.SCOPE_TYPE_DATASET, scopeKey);
            log.info("跳过 RAPTOR dataset-level 构建，知识域内没有可追溯原文 chunk: scopeKey={}, documentCount={}",
                scopeKey, documents.size());
            return RaptorBuildResult.builder().build();
        }

        long buildStartedNanos = System.nanoTime();
        log.info("开始构建 RAPTOR dataset-level 摘要树，scopeKey={}, documentCount={}, originalChunkCount={}, inputMode={}, inputCount={}, reusableSummaryCount={}",
            scopeKey, sourceDocumentIds.size(), chunks.size(), datasetInputs.inputMode(), datasetInputs.inputs().size(), reusableSummaryNodes.size());
        // 旧的 dataset-level 节点在 Python 构建成功、准备落库时才替换：
        // 若 Python 或质量门失败，上一次成功的摘要树保持可用，不出现「删了但没有新结果」的窗口。
        RaptorBuildResponse response = raptorBuildPort.build(buildDatasetRequest(DATASET_DOCUMENT_ID, DATASET_TASK_ID, datasetInputs, raptorOptions));
        if (response == null) {
            throw new IllegalStateException("Python RAPTOR dataset-level 构建接口返回为空。");
        }
        logLlmSummaryFallbacks(DATASET_DOCUMENT_ID, DATASET_TASK_ID, response.getNodes(), Boolean.TRUE.equals(raptorOptions.getRaptorLlmSummaryEnabled()));
        double qualityFloor = qualityFloor(raptorOptions);
        RaptorQualityReport sourceQualityReport = raptorQualityService.evaluatePythonNodes(response.getNodes(), qualityFloor);
        Map<String, Long> idMap = allocateNodeIds(response.getNodes(), qualityFloor);
        List<SuperAgentRaptorNode> nodes = buildNodeEntities(
            DATASET_DOCUMENT_ID,
            DATASET_TASK_ID,
            RaptorScopeSupport.SCOPE_TYPE_DATASET,
            scopeKey,
            response.getNodes(),
            idMap,
            sourceDocumentIds,
            sourceTaskIds,
            datasetInputs,
            qualityFloor
        );
        if (CollUtil.isEmpty(nodes)) {
            withdrawScopeNodes(RaptorScopeSupport.SCOPE_TYPE_DATASET, scopeKey);
            log.warn("RAPTOR dataset-level 构建没有达到质量阈值的摘要节点，已清空该范围的摘要树，scopeKey={}, qualityFloor={}",
                scopeKey, qualityFloor);
            return RaptorBuildResult.builder()
                .sourceQualityReport(sourceQualityReport)
                .savedQualityReport(raptorQualityService.evaluatePythonNodes(List.of(), qualityFloor))
                .build();
        }
        long insertStartedNanos = System.nanoTime();
        persistScopeNodes(RaptorScopeSupport.SCOPE_TYPE_DATASET, scopeKey, nodes);
        log.info("RAPTOR dataset-level 节点入库完成，scopeKey={}, nodeCount={}, costMillis={}",
            scopeKey, nodes.size(), elapsedMillis(insertStartedNanos));
        vectorize(nodes);
        indexSummaries(nodes);

        int levelCount = nodes.stream()
            .map(SuperAgentRaptorNode::getNodeLevel)
            .filter(Objects::nonNull)
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll)
            .size();
        int sourceChunkCount = nodes.stream()
            .flatMap(node -> readLongList(node.getSourceChunkIdsJson()).stream())
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll)
            .size();
        RaptorBuildResult result = RaptorBuildResult.builder()
            .nodeCount(nodes.size())
            .levelCount(levelCount)
            .sourceChunkCount(sourceChunkCount)
            .inputMode(datasetInputs.inputMode())
            .inputCount(datasetInputs.inputs().size())
            .reusableSummaryInputCount(datasetInputs.reusableSummaryInputCount())
            .originalChunkInputCount(datasetInputs.originalChunkInputCount())
            .sourceQualityReport(sourceQualityReport)
            .savedQualityReport(raptorQualityService.evaluate(nodes, qualityFloor))
            .build();
        log.info("RAPTOR dataset-level 摘要树构建完成: scopeKey={}, documentCount={}, inputMode={}, inputCount={}, nodeCount={}, levelCount={}, sourceChunkCount={}, costMillis={}",
            scopeKey, sourceDocumentIds.size(), result.getInputMode(), result.getInputCount(), result.getNodeCount(), result.getLevelCount(), result.getSourceChunkCount(), elapsedMillis(buildStartedNanos));
        return result;
    }

    /** 短事务：撤回某个文档任务的 RAPTOR 节点。 */
    private void withdrawDocumentNodes(Long documentId, Long taskId) {
        transactionTemplate.executeWithoutResult(status -> deleteByTask(documentId, taskId));
    }

    /** 短事务：撤回某个范围的 RAPTOR 节点。 */
    private void withdrawScopeNodes(String scopeType, String scopeKey) {
        transactionTemplate.executeWithoutResult(status -> deleteByScope(scopeType, scopeKey));
    }

    /** 短事务：替换某个文档任务的 RAPTOR 节点。外部调用必须在事务之外完成。 */
    private void persistDocumentNodes(Long documentId, Long taskId, List<SuperAgentRaptorNode> nodes) {
        transactionTemplate.executeWithoutResult(status -> {
            deleteByTask(documentId, taskId);
            MybatisBatchExecutor.insertBatch(SuperAgentRaptorNode.class, nodes);
        });
    }

    /** 短事务：替换某个范围的 RAPTOR 节点。外部调用必须在事务之外完成。 */
    private void persistScopeNodes(String scopeType, String scopeKey, List<SuperAgentRaptorNode> nodes) {
        transactionTemplate.executeWithoutResult(status -> {
            deleteByScope(scopeType, scopeKey);
            MybatisBatchExecutor.insertBatch(SuperAgentRaptorNode.class, nodes);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        raptorNodeMapper.delete(new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getDocumentId, documentId)
            .eq(SuperAgentRaptorNode::getTaskId, taskId));
        pgVectorOperations.update(requireDocumentTenant(documentId),
            DELETE_VECTOR_BY_TASK_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.RAPTOR_EMBEDDING_TABLE_NAME),
            documentId, taskId);
        RaptorSummaryIndexService indexService = raptorSummaryIndexServiceProvider.getIfAvailable();
        if (indexService != null) {
            indexService.deleteByTask(documentId, taskId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        raptorNodeMapper.delete(new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getDocumentId, documentId));
        pgVectorOperations.update(requireDocumentTenant(documentId),
            DELETE_VECTOR_BY_DOCUMENT_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.RAPTOR_EMBEDDING_TABLE_NAME),
            documentId);
        RaptorSummaryIndexService indexService = raptorSummaryIndexServiceProvider.getIfAvailable();
        if (indexService != null) {
            indexService.deleteByDocumentId(documentId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByScope(String scopeType, String scopeKey) {
        if (StrUtil.isBlank(scopeType) || StrUtil.isBlank(scopeKey)) {
            return;
        }
        List<SuperAgentRaptorNode> scopeNodes = raptorNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getScopeType, scopeType)
            .eq(SuperAgentRaptorNode::getScopeKey, scopeKey));
        raptorNodeMapper.delete(new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getScopeType, scopeType)
            .eq(SuperAgentRaptorNode::getScopeKey, scopeKey));
        // scope 是跨文档（甚至跨租户）的集合：向量行必须按各自租户的事务作用域删除，
        // 否则 RLS 只允许其中一部分行被删，剩下的会变成不可见也删不掉的残留。
        for (Long scopeTenantId : scopeTenants(scopeNodes)) {
            pgVectorOperations.update(scopeTenantId,
                DELETE_VECTOR_BY_SCOPE_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.RAPTOR_EMBEDDING_TABLE_NAME),
                scopeType, scopeKey);
        }
        RaptorSummaryIndexService indexService = raptorSummaryIndexServiceProvider.getIfAvailable();
        if (indexService != null) {
            indexService.deleteByScope(scopeType, scopeKey);
        }
    }

    private RaptorBuildRequest buildRequest(Long documentId,
                                                    Long taskId,
                                                    List<SuperAgentDocumentChunk> chunks,
                                                    KnowledgeBaseIndexingOptions.RaptorBuildOptions raptorOptions) {
        RaptorBuildRequest request = new RaptorBuildRequest();
        request.setDocumentId(documentId);
        request.setTaskId(taskId);
        request.setMaxClusterSize(raptorOptions.getRaptorMaxClusterSize());
        request.setMaxLevels(raptorOptions.getRaptorMaxLevels());
        request.setLlmSummaryEnabled(Boolean.TRUE.equals(raptorOptions.getRaptorLlmSummaryEnabled()));
        request.setLlmConcurrency(requireConfiguration().llmConcurrency());

        List<RaptorBuildRequest.Chunk> requestChunks = new ArrayList<>();
        for (SuperAgentDocumentChunk chunk : chunks) {
            if (chunk == null || chunk.getId() == null || StrUtil.isBlank(chunk.getChunkText())) {
                continue;
            }
            RaptorBuildRequest.Chunk requestChunk = new RaptorBuildRequest.Chunk();
            requestChunk.setChunkId(chunk.getId());
            requestChunk.setParentBlockId(chunk.getParentBlockId());
            requestChunk.setChunkNo(chunk.getChunkNo());
            requestChunk.setChunkType(chunk.getChunkType());
            requestChunk.setTitle(chunk.getTitle());
            requestChunk.setSectionPath(chunk.getSectionPath());
            requestChunk.setPageNo(chunk.getPageNo());
            requestChunk.setPageRange(chunk.getPageRange());
            requestChunk.setBboxJson(chunk.getBboxJson());
            requestChunk.setText(chunk.getChunkText());
            requestChunk.setContentWithWeight(StrUtil.blankToDefault(chunk.getContentWithWeight(), chunk.getChunkText()));
            requestChunk.setSourceBlockIds(chunk.getSourceBlockIds());
            requestChunk.setMetadata(chunkMetadata(chunk));
            requestChunks.add(requestChunk);
        }
        request.setChunks(requestChunks);
        return request;
    }

    private RaptorBuildRequest buildDatasetRequest(Long documentId,
                                                           Long taskId,
                                                           RaptorDatasetBuildSupport.DatasetInputs datasetInputs,
                                                           KnowledgeBaseIndexingOptions.RaptorBuildOptions raptorOptions) {
        RaptorBuildRequest request = new RaptorBuildRequest();
        request.setDocumentId(documentId);
        request.setTaskId(taskId);
        request.setMaxClusterSize(raptorOptions.getRaptorMaxClusterSize());
        request.setMaxLevels(raptorOptions.getRaptorMaxLevels());
        request.setLlmSummaryEnabled(Boolean.TRUE.equals(raptorOptions.getRaptorLlmSummaryEnabled()));
        request.setLlmConcurrency(requireConfiguration().llmConcurrency());

        List<RaptorBuildRequest.Chunk> requestChunks = new ArrayList<>();
        int chunkNo = 1;
        for (RaptorDatasetBuildSupport.DatasetInput input : datasetInputs.inputs()) {
            requestChunks.add(raptorDatasetBuildSupport.toRequestChunk(input, chunkNo++));
        }
        request.setChunks(requestChunks);
        return request;
    }

    private Map<String, Object> chunkMetadata(SuperAgentDocumentChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planId", chunk.getPlanId());
        metadata.put("sourceType", chunk.getSourceType());
        metadata.put("structureNodeId", chunk.getStructureNodeId());
        metadata.put("structureNodeType", chunk.getStructureNodeType());
        metadata.put("canonicalPath", chunk.getCanonicalPath());
        metadata.put("itemIndex", chunk.getItemIndex());
        metadata.put("keywords", chunk.getKeywords());
        metadata.put("questions", chunk.getQuestions());
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("taskId", chunk.getTaskId());
        return metadata;
    }

    private Map<String, Long> allocateNodeIds(List<RaptorBuildResponse.Node> extractedNodes,
                                              double qualityFloor) {
        Map<String, Long> idMap = new LinkedHashMap<>();
        if (CollUtil.isEmpty(extractedNodes)) {
            return idMap;
        }
        for (RaptorBuildResponse.Node node : extractedNodes) {
            if (node != null && StrUtil.isNotBlank(node.getId()) && summaryQualityScore(node) >= qualityFloor) {
                idMap.put(node.getId(), uidGenerator.getUid());
            }
        }
        return idMap;
    }

    private List<SuperAgentRaptorNode> buildNodeEntities(Long documentId,
                                                         Long taskId,
                                                         String scopeType,
                                                         String scopeKey,
                                                         List<RaptorBuildResponse.Node> extractedNodes,
                                                         Map<String, Long> idMap,
                                                         List<Long> sourceDocumentIds,
                                                         List<Long> sourceTaskIds,
                                                         RaptorDatasetBuildSupport.DatasetInputs datasetInputs,
                                                         double qualityFloor) {
        if (CollUtil.isEmpty(extractedNodes)) {
            return List.of();
        }
        List<SuperAgentRaptorNode> nodes = new ArrayList<>();
        for (RaptorBuildResponse.Node extracted : extractedNodes) {
            if (extracted == null || StrUtil.isBlank(extracted.getId())) {
                continue;
            }
            Long nodeId = idMap.get(extracted.getId());
            if (nodeId == null) {
                continue;
            }

            SuperAgentRaptorNode node = new SuperAgentRaptorNode();
            node.setId(nodeId);
            node.setDocumentId(documentId);
            node.setTaskId(taskId);
            node.setScopeType(limit(StrUtil.blankToDefault(scopeType, RaptorScopeSupport.SCOPE_TYPE_DOCUMENT), 32));
            node.setScopeKey(limit(StrUtil.blankToDefault(scopeKey, RaptorScopeSupport.documentScopeKey(documentId)), 255));
            node.setNodeKey(limit(nodeKey(scopeType, scopeKey, extracted.getId()), 255));
            node.setParentNodeId(StrUtil.isBlank(extracted.getParentId()) ? null : idMap.get(extracted.getParentId()));
            node.setNodeLevel(defaultInteger(extracted.getLevel(), 1));
            node.setNodeNo(defaultInteger(extracted.getNodeNo(), nodes.size() + 1));
            node.setTitle(limit(extracted.getTitle(), 500));
            node.setSummary(extracted.getSummary());
            node.setSummaryWithWeight(StrUtil.blankToDefault(extracted.getSummaryWithWeight(), extracted.getSummary()));
            node.setChildNodeIdsJson(writeJson(remapIds(extracted.getChildNodeIds(), idMap)));
            List<Long> sourceChunkIds = datasetInputs == null
                ? extracted.getSourceChunkIds()
                : datasetInputs.expandSourceChunkIds(extracted.getSourceChunkIds());
            List<Long> sourceParentBlockIds = datasetInputs == null
                ? extracted.getSourceParentBlockIds()
                : datasetInputs.expandSourceParentBlockIds(extracted.getSourceChunkIds());
            node.setSourceChunkIdsJson(writeJson(sourceChunkIds));
            node.setSourceParentBlockIdsJson(writeJson(sourceParentBlockIds));
            node.setSourceDocumentIdsJson(writeJson(sourceDocumentIds));
            node.setSourceTaskIdsJson(writeJson(sourceTaskIds));
            node.setSectionPath(limit(extracted.getSectionPath(), 1000));
            node.setPageRange(limit(extracted.getPageRange(), 64));
            node.setKeywords(writeJson(extracted.getKeywords()));
            node.setQuestions(writeJson(extracted.getQuestions()));
            node.setMetadataJson(writeJson(metadata(
                "sourceNodeId", extracted.getId(),
                "sourceParentId", extracted.getParentId(),
                "sourceChildNodeIds", extracted.getChildNodeIds(),
                "scopeType", node.getScopeType(),
                "scopeKey", node.getScopeKey(),
                "datasetInputMode", datasetInputs == null ? null : datasetInputs.inputMode(),
                "sourceDocumentIds", sourceDocumentIds,
                "sourceTaskIds", sourceTaskIds,
                "summaryQualityScore", summaryQualityScore(extracted),
                "sourceMetadata", extracted.getMetadata()
            )));
            if (summaryQualityScore(extracted) < qualityFloor) {
                log.info("跳过低质量 RAPTOR 摘要节点: documentId={}, taskId={}, sourceNodeId={}, qualityScore={}, floor={}",
                    documentId, taskId, extracted.getId(), summaryQualityScore(extracted), qualityFloor);
                continue;
            }
            node.setStatus(BusinessStatus.YES.getCode());
            nodes.add(node);
        }
        return nodes;
    }

    private List<Long> remapIds(List<String> sourceIds, Map<String, Long> idMap) {
        if (CollUtil.isEmpty(sourceIds)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (String sourceId : sourceIds) {
            Long id = idMap.get(sourceId);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private void vectorize(List<SuperAgentRaptorNode> nodes) {
        if (CollUtil.isEmpty(nodes)) {
            return;
        }
        EmbeddingPort embeddingModel = requireEmbeddingModel();
        String embeddingModelName = resolveEmbeddingModelName();
        String upsertSql = UPSERT_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.RAPTOR_EMBEDDING_TABLE_NAME);
        int batchSize = embeddingBatchSize();
        int totalBatchCount = (nodes.size() + batchSize - 1) / batchSize;
        // 摘要向量与文档同租户：dataset 级摘要的租户取自它的来源文档。
        Long vectorTenantId = requireRaptorTenant(nodes.get(0));
        long vectorizeStartedNanos = System.nanoTime();
        log.info("开始执行 RAPTOR 摘要向量化，nodeCount={}, batchSize={}, batchCount={}, embeddingModel={}",
            nodes.size(), batchSize, totalBatchCount, embeddingModelName);
        for (int startIndex = 0; startIndex < nodes.size(); startIndex += batchSize) {
            int endIndex = Math.min(startIndex + batchSize, nodes.size());
            List<SuperAgentRaptorNode> currentBatch = nodes.subList(startIndex, endIndex);
            int currentBatchIndex = (startIndex / batchSize) + 1;
            long batchStartedNanos = System.nanoTime();
            long embeddingStartedNanos = System.nanoTime();
            List<float[]> embeddings = embeddingModel.embed(currentBatch.stream()
                .map(this::embeddingInput)
                .toList());
            long embeddingCostMillis = elapsedMillis(embeddingStartedNanos);
            if (embeddings.size() != currentBatch.size()) {
                throw new IllegalStateException("向量化模型返回的 RAPTOR 向量数量与节点数量不一致。");
            }
            validateEmbeddingDimensions(embeddingModel, embeddings, currentBatchIndex);
            long upsertStartedNanos = System.nanoTime();
            batchUpsert(upsertSql, currentBatch, embeddings, embeddingModelName, vectorTenantId);
            log.info("RAPTOR 摘要向量化批次完成，batchIndex={}/{}, currentBatchSize={}, embeddingCostMillis={}, pgVectorCostMillis={}, batchCostMillis={}",
                currentBatchIndex, totalBatchCount, currentBatch.size(), embeddingCostMillis,
                elapsedMillis(upsertStartedNanos), elapsedMillis(batchStartedNanos));
        }
        log.info("RAPTOR 摘要向量化完成，nodeCount={}, batchSize={}, batchCount={}, embeddingModel={}, costMillis={}",
            nodes.size(), batchSize, totalBatchCount, embeddingModelName, elapsedMillis(vectorizeStartedNanos));
    }

    private void indexSummaries(List<SuperAgentRaptorNode> nodes) {
        if (CollUtil.isEmpty(nodes)) {
            return;
        }
        RaptorSummaryIndexService indexService = raptorSummaryIndexServiceProvider.getIfAvailable();
        if (indexService == null) {
            log.info("当前未启用 RAPTOR 摘要 Elasticsearch/BM25 索引服务，跳过摘要索引写入。");
            return;
        }
        indexService.indexNodes(nodes);
    }

    private void batchUpsert(String upsertSql,
                             List<SuperAgentRaptorNode> nodeBatch,
                             List<float[]> embeddingBatch,
                             String embeddingModelName,
                             Long vectorTenantId) {
        pgVectorOperations.batchUpdate(vectorTenantId, upsertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                SuperAgentRaptorNode node = nodeBatch.get(index);
                ps.setLong(1, node.getId());
                ps.setLong(2, node.getDocumentId());
                ps.setLong(3, node.getTaskId());
                ps.setString(4, node.getScopeType());
                ps.setString(5, node.getScopeKey());
                ps.setInt(6, defaultInteger(node.getNodeLevel(), 1));
                ps.setInt(7, defaultInteger(node.getNodeNo(), 0));
                if (node.getParentNodeId() == null) {
                    ps.setNull(8, Types.BIGINT);
                }
                else {
                    ps.setLong(8, node.getParentNodeId());
                }
                ps.setString(9, node.getTitle());
                ps.setString(10, node.getSummary());
                ps.setString(11, embeddingInput(node));
                ps.setString(12, node.getSourceChunkIdsJson());
                ps.setString(13, node.getSourceParentBlockIdsJson());
                ps.setString(14, node.getSourceDocumentIdsJson());
                ps.setString(15, node.getSourceTaskIdsJson());
                ps.setString(16, node.getSectionPath());
                ps.setString(17, node.getPageRange());
                ps.setString(18, node.getKeywords());
                ps.setString(19, node.getQuestions());
                ps.setString(20, embeddingModelName);
                ps.setString(21, buildMetadataJson(node, embeddingModelName));
                ps.setString(22, toVectorLiteral(embeddingBatch.get(index)));
                ps.setInt(23, BusinessStatus.YES.getCode());
                ps.setLong(24, vectorTenantId);
            }

            @Override
            public int getBatchSize() {
                return nodeBatch.size();
            }
        });
    }

    private String buildMetadataJson(SuperAgentRaptorNode node, String embeddingModelName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", node.getDocumentId());
        metadata.put("taskId", node.getTaskId());
        metadata.put("scopeType", node.getScopeType());
        metadata.put("scopeKey", node.getScopeKey());
        metadata.put("raptorNodeId", node.getId());
        metadata.put("nodeLevel", node.getNodeLevel());
        metadata.put("nodeNo", node.getNodeNo());
        metadata.put("parentNodeId", node.getParentNodeId());
        metadata.put("title", node.getTitle());
        metadata.put("sectionPath", node.getSectionPath());
        metadata.put("pageRange", node.getPageRange());
        metadata.put("keywords", node.getKeywords());
        metadata.put("questions", node.getQuestions());
        metadata.put("sourceChunkIds", readLongList(node.getSourceChunkIdsJson()));
        metadata.put("sourceParentBlockIds", readLongList(node.getSourceParentBlockIdsJson()));
        metadata.put("sourceDocumentIds", readLongList(node.getSourceDocumentIdsJson()));
        metadata.put("sourceTaskIds", readLongList(node.getSourceTaskIdsJson()));
        metadata.put("summaryQualityScore", metadataValue(node.getMetadataJson(), "summaryQualityScore"));
        metadata.put("embeddingModel", embeddingModelName);
        return writeJson(metadata);
    }

    private Long requireDocumentTenant(Long documentId) {
        Long tenantId = documentTenantLookup.tenantOfDocument(documentId);
        if (tenantId == null) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_VECTOR_FAILED.getCode(),
                "无法确定文档所属租户，拒绝操作 RAPTOR 向量数据: " + documentId);
        }
        return tenantId;
    }

    /**
     * 节点的向量租户。
     *
     * <p>dataset 级节点的 {@code document_id} 是 0 哨兵，它的租户要从来源文档取
     * （与可见性判定用的是同一组来源文档）；文档级节点直接取自己的文档租户。</p>
     */
    private Long requireRaptorTenant(SuperAgentRaptorNode node) {
        if (node.getDocumentId() != null && node.getDocumentId() > 0L) {
            return requireDocumentTenant(node.getDocumentId());
        }
        List<Long> sourceDocumentIds = readLongList(node.getSourceDocumentIdsJson());
        if (sourceDocumentIds.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_VECTOR_FAILED.getCode(),
                "RAPTOR 节点既没有文档也没有来源文档，无法确定租户，拒绝写入向量数据: " + node.getId());
        }
        Long tenantId = documentTenantLookup.tenantOfDocument(sourceDocumentIds.get(0));
        if (tenantId == null) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_VECTOR_FAILED.getCode(),
                "来源文档不存在，无法确定 RAPTOR 向量租户: " + sourceDocumentIds.get(0));
        }
        return tenantId;
    }

    /** scope 内出现过的全部租户（一个 scope 可以跨文档甚至跨租户）。 */
    private Set<Long> scopeTenants(List<SuperAgentRaptorNode> scopeNodes) {
        if (CollUtil.isEmpty(scopeNodes)) {
            return Set.of();
        }
        Set<Long> documentIds = new LinkedHashSet<>();
        for (SuperAgentRaptorNode node : scopeNodes) {
            if (node.getDocumentId() != null && node.getDocumentId() > 0L) {
                documentIds.add(node.getDocumentId());
            }
            documentIds.addAll(readLongList(node.getSourceDocumentIdsJson()));
        }
        Set<Long> tenants = documentTenantLookup.tenantsOfDocuments(documentIds).values().stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (tenants.isEmpty()) {
            log.warn("RAPTOR scope 未解析出任何租户，跳过向量删除，scopeNodeCount={}", scopeNodes.size());
        }
        return tenants;
    }

    private String embeddingInput(SuperAgentRaptorNode node) {
        return StrUtil.blankToDefault(node.getSummaryWithWeight(), node.getSummary()).trim();
    }

    private EmbeddingPort requireEmbeddingModel() {
        EmbeddingPort embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("当前未找到可用的向量化模型，无法执行 RAPTOR 摘要向量化。");
        }
        return embeddingModel;
    }

    private String resolveEmbeddingModelName() {
        return requireEmbeddingModel().model();
    }

    private int embeddingBatchSize() {
        int configured = requireConfiguration().embeddingBatchSize();
        if (configured <= 0 || configured > EMBEDDING_BATCH_SIZE_LIMIT) {
            throw new IllegalStateException("RAPTOR 向量化批次大小超出允许范围");
        }
        return configured;
    }

    private RaptorBuildConfigurationPort requireConfiguration() {
        if (configurationPort == null) {
            throw new IllegalStateException("RAPTOR build configuration provider is required");
        }
        return configurationPort;
    }

    private KnowledgeBaseIndexingOptions.RaptorBuildOptions raptorBuildOptionsByDocumentId(Long documentId) {
        if (indexingConfigResolver == null) {
            throw new IllegalStateException("知识库 RAPTOR 配置提供方不可用");
        }
        return indexingConfigResolver.resolveByDocumentId(documentId).getRaptor();
    }

    private KnowledgeBaseIndexingOptions.RaptorBuildOptions raptorBuildOptionsByKnowledgeBaseId(Long knowledgeBaseId) {
        if (indexingConfigResolver == null) {
            throw new IllegalStateException("知识库 RAPTOR 配置提供方不可用");
        }
        return indexingConfigResolver.resolveByKnowledgeBaseId(knowledgeBaseId).getRaptor();
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("向量化模型返回了空 RAPTOR 向量。");
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

    /** 写入前的最后一道维度闸门：适配器声明了维度时不允许维度漂移的向量进入 pgvector。 */
    private static void validateEmbeddingDimensions(EmbeddingPort embeddingModel, List<float[]> embeddings, int batchIndex) {
        int expected = embeddingModel.dimensions();
        if (expected <= 0) {
            return;
        }
        for (int index = 0; index < embeddings.size(); index++) {
            float[] vector = embeddings.get(index);
            if (vector == null || vector.length != expected) {
                throw new IllegalStateException("RAPTOR embedding 批次 " + batchIndex + " 第 " + index
                    + " 条维度与端口声明不一致：" + (vector == null ? "null" : vector.length) + "，期望 " + expected);
            }
        }
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

    private Map<String, Object> metadata(Object... keyValues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            Object value = keyValues[index + 1];
            if (value != null) {
                metadata.put(String.valueOf(keyValues[index]), value);
            }
        }
        return metadata;
    }

    private void logLlmSummaryFallbacks(Long documentId,
                                        Long taskId,
                                        List<RaptorBuildResponse.Node> extractedNodes,
                                        boolean llmSummaryRequested) {
        if (!llmSummaryRequested || CollUtil.isEmpty(extractedNodes)) {
            return;
        }
        for (RaptorBuildResponse.Node node : extractedNodes) {
            Map<String, Object> sourceMetadata = node == null || node.getMetadata() == null ? Map.of() : node.getMetadata();
            Map<String, Object> signals = objectMap(sourceMetadata.get("summaryQualitySignals"));
            boolean requested = booleanValue(signals.get("llmSummaryRequested"));
            String status = stringValue(signals.get("llmSummaryStatus"));
            if (!requested || "success".equals(status)) {
                continue;
            }
            log.warn("RAPTOR LLM 摘要未生效，已使用非 LLM 摘要策略: documentId={}, taskId={}, sourceNodeId={}, summaryStrategy={}, llmSummaryStatus={}, llmSummaryError={}",
                documentId,
                taskId,
                node == null ? null : node.getId(),
                stringValue(sourceMetadata.get("summaryStrategy")),
                status,
                stringValue(signals.get("llmSummaryError")));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(stringValue(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double summaryQualityScore(RaptorBuildResponse.Node extracted) {
        if (extracted == null || extracted.getQualityScore() == null) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, extracted.getQualityScore()));
    }

    private double qualityFloor(KnowledgeBaseIndexingOptions.RaptorBuildOptions raptorOptions) {
        Double configuredFloor = raptorOptions == null ? null : raptorOptions.getRaptorSummaryQualityFloor();
        if (configuredFloor == null) {
            return 0.42D;
        }
        return Math.max(0D, Math.min(1D, configuredFloor));
    }

    private Object metadataValue(String json, String key) {
        if (StrUtil.isBlank(json) || StrUtil.isBlank(key)) {
            return null;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            return metadata.get(key);
        }
        catch (Exception exception) {
            return null;
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAPTOR 元数据 JSON 序列化失败", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private int defaultInteger(Integer value, int defaultValue) {
        return Objects.requireNonNullElse(value, defaultValue);
    }

    private List<SuperAgentDocumentChunk> loadLatestChunks(List<SuperAgentDocument> documents) {
        Map<Long, Long> latestTaskIdByDocumentId = documents.stream()
            .filter(document -> document.getId() != null && document.getLastIndexTaskId() != null)
            .collect(Collectors.toMap(
                SuperAgentDocument::getId,
                SuperAgentDocument::getLastIndexTaskId,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        if (latestTaskIdByDocumentId.isEmpty()) {
            return List.of();
        }
        List<SuperAgentDocumentChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .in(SuperAgentDocumentChunk::getDocumentId, latestTaskIdByDocumentId.keySet())
            .eq(SuperAgentDocumentChunk::getSourceType, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentChunk::getDocumentId, SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId));
        return chunks.stream()
            .filter(chunk -> Objects.equals(latestTaskIdByDocumentId.get(chunk.getDocumentId()), chunk.getTaskId()))
            .toList();
    }

    private List<SuperAgentRaptorNode> loadReusableDocumentSummaryNodes(List<SuperAgentDocument> documents) {
        Map<Long, Long> latestTaskIdByDocumentId = documents.stream()
            .filter(document -> document.getId() != null && document.getLastIndexTaskId() != null)
            .collect(Collectors.toMap(
                SuperAgentDocument::getId,
                SuperAgentDocument::getLastIndexTaskId,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        if (latestTaskIdByDocumentId.isEmpty()) {
            return List.of();
        }
        List<SuperAgentRaptorNode> nodes = raptorNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .in(SuperAgentRaptorNode::getDocumentId, latestTaskIdByDocumentId.keySet())
            .eq(SuperAgentRaptorNode::getScopeType, RaptorScopeSupport.SCOPE_TYPE_DOCUMENT)
            .ge(SuperAgentRaptorNode::getNodeLevel, 2)
            .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentRaptorNode::getDocumentId, SuperAgentRaptorNode::getNodeLevel, SuperAgentRaptorNode::getNodeNo, SuperAgentRaptorNode::getId));
        return nodes.stream()
            .filter(node -> Objects.equals(latestTaskIdByDocumentId.get(node.getDocumentId()), node.getTaskId()))
            .toList();
    }

    private boolean coversAllDocuments(List<SuperAgentRaptorNode> reusableSummaryNodes, List<Long> sourceDocumentIds) {
        if (CollUtil.isEmpty(reusableSummaryNodes) || CollUtil.isEmpty(sourceDocumentIds)) {
            return false;
        }
        LinkedHashSet<Long> coveredDocumentIds = reusableSummaryNodes.stream()
            .map(SuperAgentRaptorNode::getDocumentId)
            .filter(Objects::nonNull)
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        return coveredDocumentIds.containsAll(sourceDocumentIds);
    }

    private List<Long> distinctChunkDocumentIds(List<SuperAgentDocumentChunk> chunks) {
        return CollUtil.emptyIfNull(chunks).stream()
            .map(SuperAgentDocumentChunk::getDocumentId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private List<Long> distinctChunkTaskIds(List<SuperAgentDocumentChunk> chunks) {
        return CollUtil.emptyIfNull(chunks).stream()
            .map(SuperAgentDocumentChunk::getTaskId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private String nodeKey(String scopeType, String scopeKey, String sourceNodeId) {
        if (RaptorScopeSupport.isDatasetScope(scopeType)) {
            return scopeKey + ":" + sourceNodeId;
        }
        return sourceNodeId;
    }
}
