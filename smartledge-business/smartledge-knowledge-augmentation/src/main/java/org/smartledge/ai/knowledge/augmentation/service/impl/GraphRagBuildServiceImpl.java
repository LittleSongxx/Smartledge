package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.knowledge.augmentation.config.GraphRagBuildProperties;
import org.smartledge.ai.knowledge.augmentation.config.GraphRagExecutionProperties;
import org.smartledge.ai.knowledge.augmentation.config.GraphRagExtractionOptions;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEntity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEvidence;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelation;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEntityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEvidenceMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationMapper;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionRequest;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionResponse;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagBuildResult;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEntityResolutionAdvice;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEntityResolutionContext;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagExtractionAdvice;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagExtractionContext;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagExtractionPort;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagLlmConfigurationPort;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagTableProjectionPort;
import org.smartledge.ai.knowledge.augmentation.port.KnowledgeBaseAugmentationConfigurationPort;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagBuildCheckpointService;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagBuildService;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagCrossDocumentIndexService;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagEntityResolutionAdvisor;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagBatchExecutor;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagBuildFailureException;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagBuildOutcomePolicy;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagBuildStoppedException;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCandidateExecution;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagRejectionDiagnostics;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagExtractionContractValidator;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagRelationAuthority;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.smartledge.ai.manage.model.KnowledgeBaseIndexingOptions;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentChunkSourceTypeEnum;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;
import org.smartledge.lease.RedisLeaseManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GraphRagBuildServiceImpl implements GraphRagBuildService {

    private static final String RANK_ALGORITHM = "java.pagerank.v1";
    private static final String GRAPH_EXTRACTION_STRATEGY_LLM = "python.unified.candidates.v3";
    private static final String GRAPH_EXTRACTION_ADVISOR_METADATA_KEY = "candidateValidation";
    private static final Pattern DIRECT_ACTION_SEGMENT_BOUNDARY = Pattern
            .compile("[。！？.!?；;]+|\\R+(?![\\t ]*(?:[-*+]|\\d+[.)、]))");
    private static final int RANK_ITERATIONS = 30;
    private static final double RANK_DAMPING = 0.85D;
    private static final double GRAPH_EXTRACTION_CONFIDENCE_THRESHOLD = GraphRagExtractionAdvice.MIN_ACCEPTED_CONFIDENCE;
    private static final double ENTITY_RESOLUTION_CONFIDENCE_THRESHOLD = 0.78D;
    private static final int ENTITY_RESOLUTION_CONTEXT_LIMIT = 80;

    private final SuperAgentKgEntityMapper entityMapper;

    private final SuperAgentKgRelationMapper relationMapper;

    private final SuperAgentKgEvidenceMapper evidenceMapper;

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final GraphRagExtractionPort graphRagExtractionPort;

    private final ObjectMapper objectMapper;

    private final UidGenerator uidGenerator;

    private final GraphRagBuildProperties buildProperties;

    private final RedisLeaseManager redisLeaseManager;

    private final GraphRagBuildCheckpointService checkpointService;

    private final TransactionTemplate transactionTemplate;

    private final GraphRagLlmConfigurationPort extractionConfiguration;

    private final GraphRagEntityResolutionAdvisor entityResolutionAdvisor;

    private final GraphRagCrossDocumentIndexService crossDocumentIndexService;

    private final KnowledgeBaseAugmentationConfigurationPort indexingConfigResolver;

    private final GraphRagExtractionContractValidator contractValidator = new GraphRagExtractionContractValidator();

    private final GraphRagBuildOutcomePolicy outcomePolicy = new GraphRagBuildOutcomePolicy();

    private GraphRagBatchExecutor batchExecutor;

    private GraphRagTableProjectionPort tableProjectionPort;

    @Autowired(required = false)
    private GraphRagRejectionDiagnostics rejectionDiagnostics;

    @Autowired
    public void setBatchExecutor(GraphRagBatchExecutor executor) {
        this.batchExecutor = executor;
    }

    @Autowired(required = false)
    public void setTableProjectionPort(GraphRagTableProjectionPort tableProjectionPort) {
        this.tableProjectionPort = tableProjectionPort;
    }

    @Autowired
    public GraphRagBuildServiceImpl(SuperAgentKgEntityMapper entityMapper, SuperAgentKgRelationMapper relationMapper,
            SuperAgentKgEvidenceMapper evidenceMapper,
            SuperAgentDocumentTaskMapper taskMapper, SuperAgentDocumentChunkMapper chunkMapper,
            GraphRagExtractionPort graphRagExtractionPort, ObjectMapper objectMapper, UidGenerator uidGenerator,
            GraphRagBuildProperties buildProperties, RedisLeaseManager redisLeaseManager,
            GraphRagBuildCheckpointService checkpointService, TransactionTemplate transactionTemplate,
            ObjectProvider<GraphRagLlmConfigurationPort> extractionConfigurationProvider,
            ObjectProvider<GraphRagEntityResolutionAdvisor> entityResolutionAdvisorProvider,
            ObjectProvider<GraphRagCrossDocumentIndexService> crossDocumentIndexServiceProvider,
            ObjectProvider<KnowledgeBaseAugmentationConfigurationPort> indexingConfigResolverProvider) {
        this(entityMapper, relationMapper, evidenceMapper, taskMapper, chunkMapper,
                graphRagExtractionPort, objectMapper, uidGenerator, buildProperties, redisLeaseManager,
                checkpointService, transactionTemplate,
                extractionConfigurationProvider == null ? null
                        : (GraphRagLlmConfigurationPort) extractionConfigurationProvider.getIfAvailable(),
                entityResolutionAdvisorProvider == null ? null
                        : (GraphRagEntityResolutionAdvisor) entityResolutionAdvisorProvider.getIfAvailable(),
                crossDocumentIndexServiceProvider == null ? null : crossDocumentIndexServiceProvider.getIfAvailable(),
                indexingConfigResolverProvider == null ? null : indexingConfigResolverProvider.getIfAvailable());
    }

    public GraphRagBuildServiceImpl(SuperAgentKgEntityMapper entityMapper, SuperAgentKgRelationMapper relationMapper,
            SuperAgentKgEvidenceMapper evidenceMapper,
            SuperAgentDocumentTaskMapper taskMapper, SuperAgentDocumentChunkMapper chunkMapper,
            GraphRagExtractionPort graphRagExtractionPort, ObjectMapper objectMapper, UidGenerator uidGenerator,
            GraphRagBuildProperties buildProperties, RedisLeaseManager redisLeaseManager,
            GraphRagBuildCheckpointService checkpointService, TransactionTemplate transactionTemplate) {
        this(entityMapper, relationMapper, evidenceMapper, taskMapper, chunkMapper,
                graphRagExtractionPort, objectMapper, uidGenerator, buildProperties, redisLeaseManager,
                checkpointService, transactionTemplate, (GraphRagLlmConfigurationPort) null,
                (GraphRagEntityResolutionAdvisor) null, null, null);
    }

    GraphRagBuildServiceImpl(SuperAgentKgEntityMapper entityMapper, SuperAgentKgRelationMapper relationMapper,
            SuperAgentKgEvidenceMapper evidenceMapper,
            SuperAgentDocumentTaskMapper taskMapper, SuperAgentDocumentChunkMapper chunkMapper,
            GraphRagExtractionPort graphRagExtractionPort, ObjectMapper objectMapper, UidGenerator uidGenerator,
            GraphRagBuildProperties buildProperties, RedisLeaseManager redisLeaseManager,
            GraphRagBuildCheckpointService checkpointService, TransactionTemplate transactionTemplate,
            GraphRagLlmConfigurationPort extractionConfiguration,
            GraphRagEntityResolutionAdvisor entityResolutionAdvisor,
            GraphRagCrossDocumentIndexService crossDocumentIndexService) {
        this(entityMapper, relationMapper, evidenceMapper, taskMapper, chunkMapper,
                graphRagExtractionPort, objectMapper, uidGenerator, buildProperties, redisLeaseManager,
                checkpointService, transactionTemplate, extractionConfiguration,
                entityResolutionAdvisor, crossDocumentIndexService, null);
    }

    GraphRagBuildServiceImpl(SuperAgentKgEntityMapper entityMapper, SuperAgentKgRelationMapper relationMapper,
            SuperAgentKgEvidenceMapper evidenceMapper,
            SuperAgentDocumentTaskMapper taskMapper, SuperAgentDocumentChunkMapper chunkMapper,
            GraphRagExtractionPort graphRagExtractionPort, ObjectMapper objectMapper, UidGenerator uidGenerator,
            GraphRagBuildProperties buildProperties, RedisLeaseManager redisLeaseManager,
            GraphRagBuildCheckpointService checkpointService, TransactionTemplate transactionTemplate,
            GraphRagLlmConfigurationPort extractionConfiguration,
            GraphRagEntityResolutionAdvisor entityResolutionAdvisor,
            GraphRagCrossDocumentIndexService crossDocumentIndexService,
            KnowledgeBaseAugmentationConfigurationPort indexingConfigResolver) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.evidenceMapper = evidenceMapper;
        this.taskMapper = taskMapper;
        this.chunkMapper = chunkMapper;
        this.graphRagExtractionPort = graphRagExtractionPort;
        this.objectMapper = objectMapper;
        this.uidGenerator = uidGenerator;
        this.buildProperties = buildProperties;
        this.redisLeaseManager = redisLeaseManager;
        this.checkpointService = checkpointService;
        this.transactionTemplate = transactionTemplate;
        this.extractionConfiguration = extractionConfiguration;
        this.entityResolutionAdvisor = entityResolutionAdvisor;
        this.crossDocumentIndexService = crossDocumentIndexService;
        this.indexingConfigResolver = indexingConfigResolver;
    }

    @Override
    public GraphRagBuildResult rebuildDocumentGraph(Long documentId, Long taskId,
            List<SuperAgentDocumentChunk> chunks) {
        if (documentId == null || taskId == null) {
            throw failure("FAILED_LINEAGE: documentId and frozen taskId are required", null, 0,
                    GraphRagBuildResult.InvocationOutcome.NOT_CALLED, GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                    Map.of());
        }
        validateChunkLineage(documentId, taskId, chunks);
        if (!buildProperties.isLeaseEnabled()) {
            throw failure("FAILED_CONCURRENCY: graphRag.build.leaseEnabled=false cannot publish GraphRAG", null, 0,
                    GraphRagBuildResult.InvocationOutcome.NOT_CALLED, GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                    Map.of("leaseEnabled", false));
        }

        int maxAttempts = maxAttempts();
        String leaseKey = leaseKey(taskId);
        String ownerToken = UUID.randomUUID().toString();
        Duration leaseTtl = leaseTtl();
        boolean leaseAcquired = acquireLease(documentId, taskId, leaseKey, ownerToken, leaseTtl, maxAttempts);

        try {
            KnowledgeBaseIndexingOptions.GraphRagBuildOptions graphRagOptions = graphRagBuildOptions(documentId);
            if (!Boolean.TRUE.equals(graphRagOptions.getGraphRagBuildEnabled())) {
                return replaceWithExplicitEmpty(documentId, taskId, leaseKey, ownerToken, leaseTtl, maxAttempts,
                        "DISABLED_BY_KB_CONFIG");
            }
            return rebuildWithRetry(documentId, taskId, chunks, leaseKey, ownerToken, leaseTtl, maxAttempts);
        }
        finally {
            releaseLease(leaseAcquired, leaseKey, ownerToken, documentId, taskId);
        }
    }

    private GraphRagBuildResult rebuildWithRetry(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks,
            String leaseKey, String ownerToken, Duration leaseTtl, int maxAttempts) {
        GraphRagExtractionRequest request = buildRequest(documentId, taskId, chunks);
        if (CollUtil.isEmpty(request.getChunks())) {
            return replaceWithExplicitEmpty(documentId, taskId, leaseKey, ownerToken, leaseTtl, maxAttempts,
                    "EMPTY_INPUT");
        }
        GraphRagExtractionResponse extracted = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String stage = "EXTRACTING";
            try {
                checkpointService.markRunning(documentId, taskId, stage, attempt, maxAttempts,
                        metadata("chunkCount", size(request.getChunks()), "leaseKey", leaseKey));

                GraphRagExtractionResponse response;
                try {
                    if (extracted == null) {
                        extracted = executeCandidates(request, leaseKey, ownerToken, leaseTtl);
                    }
                    response = objectMapper.convertValue(extracted, GraphRagExtractionResponse.class);
                }
                catch (GraphRagCandidateExecution.CandidateExecutionException exception) {
                    if (exception.getCause() instanceof GraphRagBuildStoppedException stopped) {
                        throw new GraphRagBuildStoppedException(stopped.reason(), exception.metadata());
                    }
                    throw failure(exception.getMessage(), exception, attempt,
                            GraphRagBuildResult.InvocationOutcome.INVALID_RESPONSE,
                            GraphRagBuildResult.InvocationOutcome.NOT_CALLED, exception.metadata());
                }
                if (response == null) {
                    throw failure("INVALID_RESPONSE: Python GraphRAG response is null", null, attempt,
                            GraphRagBuildResult.InvocationOutcome.INVALID_RESPONSE,
                            GraphRagBuildResult.InvocationOutcome.NOT_CALLED, Map.of());
                }
                GraphRagExtractionContractValidator.ValidatedContract contract;
                try {
                    contract = contractValidator.validate(response, request);
                }
                catch (GraphRagExtractionContractValidator.ContractException exception) {
                    throw failure(exception.getMessage(), exception, attempt,
                            GraphRagBuildResult.InvocationOutcome.INVALID_RESPONSE,
                            GraphRagBuildResult.InvocationOutcome.NOT_CALLED, response.getMetadata());
                }
                response.setMetadata(contract.metadata());
                if (contract.observationDegraded()) {
                    log.warn(
                            "Python GraphRAG observation metadata mismatch; preserving validated grounded candidates: "
                                    + "documentId={}, taskId={}, attempt={}, warnings={}",
                            documentId, taskId, attempt, contract.observationWarnings());
                }
                response = applyGraphExtractionAdvice(documentId, taskId, attempt, request, response);

                stage = "EXTRACTED";
                checkpointService.markRunning(documentId, taskId, stage, attempt, maxAttempts,
                        extractionCheckpointMetadata(response));

                stage = "PERSISTING";
                checkpointService.markRunning(documentId, taskId, stage, attempt, maxAttempts,
                        extractionCheckpointMetadata(response));

                PreparedGraph preparedGraph = prepareGraph(documentId, taskId, response);
                GraphRagBuildResult result = commitPreparedGraph(documentId, taskId, preparedGraph,
                        buildCommittedResult(contract, response, preparedGraph, attempt, maxAttempts), leaseKey,
                        ownerToken, leaseTtl);
                result = refreshCrossDocumentIndex(documentId, taskId, result);
                result = projectFinalObservation(documentId, taskId, result, attempt, maxAttempts);
                log.info("GraphRAG 实体关系图谱构建完成: documentId={}, taskId={}, attempt={}, result={}", documentId, taskId,
                        attempt, result);
                return result;
            }
            catch (GraphRagBuildStoppedException exception) {
                throw exception;
            }
            catch (GraphRagBuildFailureException exception) {
                checkTaskActive(taskId);
                renewLeaseOrFail(leaseKey, ownerToken, leaseTtl);
                checkpointService.markFailure(documentId, taskId, stage, attempt, maxAttempts, exception);
                throw exception;
            }
            catch (RuntimeException exception) {
                if (attempt >= maxAttempts) {
                    GraphRagBuildFailureException failure = failure(
                            "NO_COMMIT: GraphRAG stage failed: stage=" + stage + ", " + exception.getMessage(),
                            exception, attempt,
                            extracted == null ? GraphRagBuildResult.InvocationOutcome.NOT_CALLED
                                    : GraphRagBuildResult.InvocationOutcome.SUCCESS,
                            GraphRagBuildResult.InvocationOutcome.NOT_CALLED, Map.of());
                    checkpointService.markFailure(documentId, taskId, stage, attempt, maxAttempts, failure);
                    throw failure;
                }
                long backoffMillis = retryBackoffMillis(attempt);
                checkpointService.markRetry(documentId, taskId, stage, attempt, maxAttempts, backoffMillis, exception);
                sleepBeforeRetry(documentId, taskId, stage, attempt, maxAttempts, backoffMillis);
            }
        }
        throw failure("NO_COMMIT: GraphRAG build did not execute", null, maxAttempts,
                GraphRagBuildResult.InvocationOutcome.NOT_CALLED, GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                Map.of());
    }

    private GraphRagBuildResult refreshCrossDocumentIndex(Long documentId, Long taskId, GraphRagBuildResult result) {
        if (crossDocumentIndexService == null) {
            return outcomePolicy.withCrossDocumentOutcome(result, GraphRagBuildResult.ComponentOutcome.FAILED);
        }
        try {
            crossDocumentIndexService.rebuildAll(documentId, taskId);
            return outcomePolicy.withCrossDocumentOutcome(result, GraphRagBuildResult.ComponentOutcome.SUCCESS);
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG 跨文档派生索引刷新失败，保留本次文档 KG 入库结果: documentId={}, taskId={}, message={}", documentId, taskId,
                    exception.getMessage());
            return outcomePolicy.withCrossDocumentOutcome(result, GraphRagBuildResult.ComponentOutcome.FAILED);
        }
    }

    private GraphRagBuildResult commitPreparedGraph(Long documentId, Long taskId, PreparedGraph preparedGraph,
            GraphRagBuildResult result, String leaseKey, String ownerToken, Duration leaseTtl) {
        try {
            return transactionTemplate.execute(status -> {
                checkTaskActive(taskId);
                renewLeaseOrFail(leaseKey, ownerToken, leaseTtl);
                deleteByTaskInternal(documentId, taskId);
                insertEntities(preparedGraph.entities().entitiesById().values());
                insertRelations(preparedGraph.relations().relationsById().values());
                insertEvidences(preparedGraph.evidences().evidencesById().values());
                return result;
            });
        }
        catch (GraphRagBuildStoppedException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw failure("NO_COMMIT: KG transaction rolled back: " + exception.getMessage(), exception,
                    result.getAttempt() == null ? 0 : result.getAttempt(), result.getPythonInvocationOutcome(),
                    result.getAdvisorInvocationOutcome(), result.getExtractionMetadata());
        }
    }

    private GraphRagBuildResult replaceWithExplicitEmpty(Long documentId, Long taskId, String leaseKey,
            String ownerToken, Duration leaseTtl, int maxAttempts, String reason) {
        checkpointService.markRunning(documentId, taskId, reason, 0, maxAttempts,
                metadata("pythonInvocationOutcome", GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                        "advisorInvocationOutcome", GraphRagBuildResult.InvocationOutcome.NOT_CALLED));
        GraphRagExtractionResponse response = new GraphRagExtractionResponse();
        PreparedGraph preparedGraph = prepareGraph(documentId, taskId, response);
        GraphRagBuildResult result = GraphRagBuildResult.builder().entityCount(0).relationCount(0).evidenceCount(0)
                .graphPersistenceOutcome(GraphRagBuildResult.GraphPersistenceOutcome.EMPTY)
                .graphPersistenceReason(reason).kgCommitted(true)
                .pythonInvocationOutcome(GraphRagBuildResult.InvocationOutcome.NOT_CALLED)
                .advisorInvocationOutcome(GraphRagBuildResult.InvocationOutcome.NOT_CALLED)
                .degradationReasons(List.of(reason)).attempt(0).maxAttempts(maxAttempts).build();
        result = commitPreparedGraph(documentId, taskId, preparedGraph, result, leaseKey, ownerToken, leaseTtl);
        result = refreshCrossDocumentIndex(documentId, taskId, result);
        return projectFinalObservation(documentId, taskId, result, 0, maxAttempts);
    }

    private GraphRagBuildResult buildCommittedResult(GraphRagExtractionContractValidator.ValidatedContract contract,
            GraphRagExtractionResponse response, PreparedGraph preparedGraph, int attempt, int maxAttempts) {
        GraphRagBuildResult.InvocationOutcome advisorOutcome = GraphRagBuildResult.InvocationOutcome.NOT_CALLED;
        int entityCount = preparedGraph.entities().entitiesById().size();
        int relationCount = preparedGraph.relations().relationsById().size();
        GraphRagBuildResult.GraphPersistenceOutcome persistenceOutcome;
        if (relationCount > 0) {
            persistenceOutcome = contract.degraded() || contract.empty()
                    ? GraphRagBuildResult.GraphPersistenceOutcome.DEGRADED
                    : GraphRagBuildResult.GraphPersistenceOutcome.SUCCESS;
        }
        else if (entityCount > 0) {
            persistenceOutcome = GraphRagBuildResult.GraphPersistenceOutcome.DEGRADED;
        }
        else {
            persistenceOutcome = GraphRagBuildResult.GraphPersistenceOutcome.EMPTY;
        }
        List<String> degradationReasons = degradationReasons(contract, persistenceOutcome, advisorOutcome,
                relationCount);
        return GraphRagBuildResult.builder().entityCount(entityCount).relationCount(relationCount)
                .evidenceCount(preparedGraph.evidences().evidencesById().size())
                .graphPersistenceOutcome(persistenceOutcome)
                .graphPersistenceReason(persistenceReason(persistenceOutcome, contract, advisorOutcome))
                .kgCommitted(true).pythonInvocationOutcome(GraphRagBuildResult.InvocationOutcome.SUCCESS)
                .advisorInvocationOutcome(advisorOutcome).pythonExtractionStatus(contract.status())
                .advisorReason(advisorObservationValue(response, "reason")).degradationReasons(degradationReasons)
                .extractionMetadata(response.getMetadata() == null ? Map.of() : response.getMetadata()).attempt(attempt)
                .maxAttempts(maxAttempts).build();
    }

    private List<String> degradationReasons(GraphRagExtractionContractValidator.ValidatedContract contract,
            GraphRagBuildResult.GraphPersistenceOutcome persistenceOutcome,
            GraphRagBuildResult.InvocationOutcome advisorOutcome, int relationCount) {
        if (persistenceOutcome == GraphRagBuildResult.GraphPersistenceOutcome.SUCCESS) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        if (contract.reportedDegraded()) {
            contract.reasonCodes().stream()
                    .filter(reason -> !"NO_GROUNDED_RELATION_CANDIDATE".equals(reason) || relationCount == 0)
                    .forEach(reasons::add);
        }
        else if (contract.empty()) {
            reasons.add(relationCount > 0 ? "PYTHON_EMPTY" : "EMPTY_GROUNDED_GRAPH");
        }
        else if (relationCount == 0) {
            reasons.add("NO_GROUNDED_RELATION_CANDIDATE");
        }
        if (contract.observationDegraded()) {
            reasons.add("PYTHON_OBSERVATION_METADATA_MISMATCH");
        }
        return reasons.stream().distinct().toList();
    }

    private String persistenceReason(GraphRagBuildResult.GraphPersistenceOutcome outcome,
            GraphRagExtractionContractValidator.ValidatedContract contract,
            GraphRagBuildResult.InvocationOutcome advisorOutcome) {
        if (outcome == GraphRagBuildResult.GraphPersistenceOutcome.SUCCESS) {
            return "GROUNDED_UNIFIED_GRAPH";
        }
        if (outcome == GraphRagBuildResult.GraphPersistenceOutcome.EMPTY) {
            boolean rejected = contract.metadata().get("candidateRejectionCount") instanceof Number count
                    && count.longValue() > 0;
            return contract.entityCount() == 0 && !rejected ? "MODEL_EMPTY_CANDIDATES" : "ALL_CANDIDATES_FILTERED";
        }
        if (contract.observationDegraded()) {
            return "PYTHON_OBSERVATION_DEGRADED";
        }
        if (contract.reportedDegraded()) {
            return "PYTHON_LAYER_DEGRADED";
        }
        if (contract.empty()) {
            return "PYTHON_EMPTY_WITH_ADVISOR_GRAPH";
        }
        return "ENTITY_ONLY";
    }

    private GraphRagBuildResult.InvocationOutcome advisorInvocationOutcome(GraphRagExtractionResponse response) {
        return switch (advisorObservationValue(response, "status")) {
        case "accepted" -> GraphRagBuildResult.InvocationOutcome.SUCCESS;
        case "empty", "filtered" -> GraphRagBuildResult.InvocationOutcome.EMPTY;
        case "not_graphable" -> GraphRagBuildResult.InvocationOutcome.NOT_GRAPHABLE;
        case "disabled" -> GraphRagBuildResult.InvocationOutcome.DISABLED;
        case "skipped" -> GraphRagBuildResult.InvocationOutcome.NOT_CALLED;
        case "failed" -> GraphRagBuildResult.InvocationOutcome.FAILED;
        default -> GraphRagBuildResult.InvocationOutcome.INVALID_RESPONSE;
        };
    }

    private String advisorObservationValue(GraphRagExtractionResponse response, String key) {
        if (response == null || response.getMetadata() == null) {
            return "";
        }
        Object observation = response.getMetadata().get(GRAPH_EXTRACTION_ADVISOR_METADATA_KEY);
        if (!(observation instanceof Map<?, ?> map)) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private GraphRagBuildResult projectFinalObservation(Long documentId, Long taskId, GraphRagBuildResult result,
            int attempt, int maxAttempts) {
        GraphRagBuildResult projected = outcomePolicy.withObservationOutcome(result,
                GraphRagBuildResult.ObservationProjectionOutcome.SUCCESS);
        try {
            checkpointService.markOutcome(documentId, taskId, projected, attempt, maxAttempts);
            return projected;
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG final observation projection failed: documentId={}, taskId={}, message={}", documentId,
                    taskId, exception.getMessage());
            return outcomePolicy.withObservationOutcome(result,
                    GraphRagBuildResult.ObservationProjectionOutcome.FAILED);
        }
    }

    private void validateChunkLineage(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks) {
        SuperAgentDocumentTask indexTask = taskMapper.selectById(taskId);
        if (indexTask == null || !Objects.equals(documentId, indexTask.getDocumentId())
                || !Objects.equals(indexTask.getTaskType(), DocumentTaskTypeEnum.BUILD_INDEX.getCode())
                || !Objects.equals(indexTask.getTaskStatus(), DocumentTaskStatusEnum.RUNNING.getCode())
                || !Objects.equals(indexTask.getStatus(), BusinessStatus.YES.getCode())
                || indexTask.getSourceParseTaskId() == null) {
            throw failure("FAILED_LINEAGE: frozen index task I is missing or invalid", null, 0,
                    GraphRagBuildResult.InvocationOutcome.NOT_CALLED, GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                    Map.of());
        }
        SuperAgentDocumentTask parseTask = taskMapper.selectById(indexTask.getSourceParseTaskId());
        if (parseTask == null || !Objects.equals(documentId, parseTask.getDocumentId())
                || !Objects.equals(parseTask.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())
                || !Objects.equals(parseTask.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())
                || !Objects.equals(parseTask.getStatus(), BusinessStatus.YES.getCode())) {
            throw failure("FAILED_LINEAGE: frozen source parse task P is missing or invalid", null, 0,
                    GraphRagBuildResult.InvocationOutcome.NOT_CALLED, GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                    Map.of());
        }
        if (chunks == null) {
            return;
        }
        Set<Long> chunkIds = new LinkedHashSet<>();
        for (SuperAgentDocumentChunk chunk : chunks) {
            if (chunk == null || chunk.getId() == null || !Objects.equals(documentId, chunk.getDocumentId())
                    || !Objects.equals(taskId, chunk.getTaskId())
                    || !Objects.equals(indexTask.getPlanId(), chunk.getPlanId()) || !chunkIds.add(chunk.getId())) {
                throw failure("FAILED_LINEAGE: every raw chunk must belong to frozen D/I", null, 0,
                        GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                        GraphRagBuildResult.InvocationOutcome.NOT_CALLED, Map.of());
            }
        }
        if (chunkIds.isEmpty()) {
            return;
        }
        List<SuperAgentDocumentChunk> persistedChunks = chunkMapper.selectBatchIds(chunkIds);
        Map<Long, SuperAgentDocumentChunk> persistedById = new LinkedHashMap<>();
        if (persistedChunks != null) {
            for (SuperAgentDocumentChunk chunk : persistedChunks) {
                if (chunk != null && chunk.getId() != null) {
                    persistedById.put(chunk.getId(), chunk);
                }
            }
        }
        for (Long chunkId : chunkIds) {
            SuperAgentDocumentChunk persisted = persistedById.get(chunkId);
            SuperAgentDocumentChunk supplied = chunks.stream().filter(c -> chunkId.equals(c.getId())).findFirst()
                    .orElseThrow();
            if (persisted == null || !Objects.equals(documentId, persisted.getDocumentId())
                    || !Objects.equals(taskId, persisted.getTaskId())
                    || Objects.equals(persisted.getSourceType(), DocumentChunkSourceTypeEnum.GRAPH_RAG.getCode())
                    || !Objects.equals(persisted.getStatus(), BusinessStatus.YES.getCode())) {
                throw failure("FAILED_LINEAGE: persisted raw chunk does not belong to frozen D/I", null, 0,
                        GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                        GraphRagBuildResult.InvocationOutcome.NOT_CALLED, Map.of());
            }
            if (!Objects.equals(persisted.getPlanId(), supplied.getPlanId())
                    || !Objects.equals(persisted.getChunkText(), supplied.getChunkText())
                    || !Objects.equals(persisted.getParentBlockId(), supplied.getParentBlockId())
                    || !Objects.equals(persisted.getSourceBlockIds(), supplied.getSourceBlockIds())
                    || !Objects.equals(persisted.getPageNo(), supplied.getPageNo())
                    || !Objects.equals(persisted.getPageRange(), supplied.getPageRange())
                    || !Objects.equals(persisted.getBboxJson(), supplied.getBboxJson())) {
                throw failure("FAILED_LINEAGE: source content or provenance differs from persisted chunk", null, 0,
                        GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                        GraphRagBuildResult.InvocationOutcome.NOT_CALLED, Map.of());
            }
        }
    }

    private GraphRagBuildFailureException failure(String message, Throwable cause, int attempt,
            GraphRagBuildResult.InvocationOutcome pythonOutcome, GraphRagBuildResult.InvocationOutcome advisorOutcome,
            Map<String, Object> extractionMetadata) {
        GraphRagBuildResult result = outcomePolicy
                .preCommitFailure(message, pythonOutcome, advisorOutcome,
                        extractionMetadata == null ? Map.of() : extractionMetadata, attempt)
                .toBuilder().maxAttempts(maxAttempts()).build();
        return cause == null ? new GraphRagBuildFailureException(message, result)
                : new GraphRagBuildFailureException(message, cause, result);
    }

    private Map<String, Object> extractionCheckpointMetadata(GraphRagExtractionResponse response) {
        return metadata("entityCount", size(response == null ? null : response.getEntities()), "relationCount",
                size(response == null ? null : response.getRelations()), "evidenceCount",
                size(response == null ? null : response.getEvidences()), "extractorMetadata",
                response == null ? null : response.getMetadata());
    }

    private PreparedGraph prepareGraph(Long documentId, Long taskId, GraphRagExtractionResponse response) {
        SavedEntities savedEntities = prepareEntities(documentId, taskId, response.getEntities());
        SavedRelations savedRelations = prepareRelations(documentId, taskId, response.getRelations(),
                savedEntities.sourceIdToEntityId());
        enrichGraphRankMetadata(savedEntities.entitiesById(), savedRelations.relationsById());
        SavedEvidences savedEvidences = prepareEvidences(documentId, taskId, response.getEvidences(),
                savedEntities.sourceIdToEntityId(), savedRelations.sourceIdToRelationId());
        return new PreparedGraph(savedEntities, savedRelations, savedEvidences);
    }

    private GraphRagExtractionResponse applyGraphExtractionAdvice(Long documentId, Long taskId, int attempt,
            GraphRagExtractionRequest request, GraphRagExtractionResponse response) {
        GraphRagExtractionContext context = buildGraphExtractionContext(documentId, taskId, request);
        GraphRagExtractionAdvice extractionAdvice = GraphRagExtractionAdvice.builder().graphable(true).confidence(1D)
                .reason("UNIFIED_PYTHON_CANDIDATES")
                .entities(response.getEntities().stream()
                        .map(entity -> entity == null ? null : GraphRagExtractionAdvice.EntityItem.builder().id(entity.getId())
                                .name(entity.getName()).normalizedName(entity.getNormalizedName())
                                .entityType(entity.getType()).aliases(entity.getAliases())
                                .description(entity.getDescription()).confidence(entity.getConfidence())
                                .sourceChunkIds(entity.getSourceChunkIds()).build())
                        .toList())
                .relations(
                        response.getRelations().stream()
                                .map(relation -> relation == null ? null : GraphRagExtractionAdvice.RelationItem.builder().id(relation.getId())
                                        .sourceEntityId(relation.getSourceEntityId())
                                        .targetEntityId(relation.getTargetEntityId())
                                        .relationType(relation.getRelationType()).description(relation.getDescription())
                                        .weight(relation.getWeight()).confidence(relation.getConfidence())
                                        .supportMode(candidateString(relation.getMetadata(), "supportMode"))
                                        .predicateQuoteText(
                                                candidateString(relation.getMetadata(), "predicateQuoteText"))
                                        .tableId(candidateLong(relation.getMetadata(), "tableId"))
                                        .rowNo(candidateInteger(relation.getMetadata(), "rowNo"))
                                        .sourceColumnNo(candidateInteger(relation.getMetadata(), "sourceColumnNo"))
                                        .targetColumnNo(candidateInteger(relation.getMetadata(), "targetColumnNo"))
                                        .build())
                                .toList())
                .evidences(response.getEvidences().stream()
                        .map(evidence -> evidence == null ? null : GraphRagExtractionAdvice.EvidenceItem.builder().id(evidence.getId())
                                .entityId(evidence.getEntityId()).relationId(evidence.getRelationId())
                                .chunkId(evidence.getChunkId()).quoteText(evidence.getQuoteText())
                                .metadata(evidence.getMetadata())
                                .confidence(candidateConfidence(evidence.getMetadata())).build())
                        .toList())
                .build();
        // Only validated candidates may reach prepareGraph; do not retain raw Python candidates.
        response.setEntities(new ArrayList<>());
        response.setRelations(new ArrayList<>());
        response.setEvidences(new ArrayList<>());

        GraphExtractionValidation validation = validateGraphExtractionAdvice(extractionAdvice, context);
        if (rejectionDiagnostics != null) {
            List<String> reasons = new ArrayList<>();
            reasons.addAll(validation.rejectedRelationReasons());
            reasons.addAll(validation.rejectedEvidenceReasons());
            reasons.addAll(validation.rejectedRelationEvidenceReasons());
            reasons.addAll(validation.rejectedEntityReasons());
            Map<String, Object> metadata = new LinkedHashMap<>(response.getMetadata());
            metadata.put("rejectionDiagnostics", rejectionDiagnostics.write(context, extractionAdvice, reasons));
            response.setMetadata(metadata);
        }
        if (!validation.enhanced()) {
            response = attachGraphExtractionAdvisorObservation(response,
                    graphExtractionAdvisorObservation(true, true, "filtered", validation.rejectedReason(),
                            extractionAdvice, validation, null, size(context.getChunks())));
            log.warn(
                    "GraphRAG advisor response contained no persistable candidates: documentId={}, taskId={}, "
                            + "attempt={}, reason={}, adviceEntityCount={}, adviceRelationCount={}, "
                            + "adviceEvidenceCount={}, contextChunkCount={}",
                    documentId, taskId, attempt, validation.rejectedReason(), size(extractionAdvice.getEntities()),
                    size(extractionAdvice.getRelations()), size(extractionAdvice.getEvidences()),
                    size(context.getChunks()));
            return response;
        }
        if (CollUtil.isNotEmpty(validation.rejectedEntityReasons())
                || CollUtil.isNotEmpty(validation.rejectedEvidenceReasons())
                || CollUtil.isNotEmpty(validation.rejectedRelationReasons())
                || CollUtil.isNotEmpty(validation.rejectedRelationEvidenceReasons())) {
            log.warn(
                    "GraphRAG advisor candidates filtered: documentId={}, taskId={}, attempt={}, "
                            + "rejectedEntityCount={}, rejectedEvidenceCount={}, rejectedRelationCount={}, "
                            + "rejectedRelationEvidenceCount={}, entityReasons={}, evidenceReasons={}, "
                            + "relationReasons={}, relationEvidenceReasons={}",
                    documentId, taskId, attempt, validation.rejectedEntityReasons().size(),
                    validation.rejectedEvidenceCount(), validation.rejectedRelationReasons().size(),
                    validation.rejectedRelationEvidenceCount(), validation.rejectedEntityReasons(),
                    validation.rejectedEvidenceReasons(), validation.rejectedRelationReasons(),
                    validation.rejectedRelationEvidenceReasons());
        }
        GraphRagExtractionResponse merged = mergeGraphExtractionResponse(response, validation);
        return attachGraphExtractionAdvisorObservation(merged, graphExtractionAdvisorObservation(true, true, "accepted",
                validation.reason(), extractionAdvice, validation, null, size(context.getChunks())));
    }

    private String candidateString(Map<String, Object> metadata, String key) {
        return metadata != null && metadata.get(key) instanceof String value ? value : "";
    }

    private Double candidateConfidence(Map<String, Object> metadata) {
        return metadata != null && metadata.get("confidence") instanceof Number value ? value.doubleValue() : null;
    }

    private Long candidateLong(Map<String, Object> metadata, String key) {
        return metadata != null && metadata.get(key) instanceof Number value ? value.longValue() : null;
    }

    private Integer candidateInteger(Map<String, Object> metadata, String key) {
        return metadata != null && metadata.get(key) instanceof Number value ? value.intValue() : null;
    }

    private String advisorFailureMessage(RuntimeException exception) {
        String message = StrUtil.blankToDefault(exception.getMessage(), "no message")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("(?i)(authorization|api[-_ ]?key|access[-_ ]?token|secret|password)\\s*[:=]\\s*[^\\s,;]+",
                        "$1=[REDACTED]");
        return limit(exception.getClass().getSimpleName() + ": " + message, 300);
    }

    private GraphRagExtractionResponse attachGraphExtractionAdvisorObservation(GraphRagExtractionResponse response,
            Map<String, Object> observation) {
        if (response == null || observation == null || observation.isEmpty()) {
            return response;
        }
        Map<String, Object> metadata = copyMetadata(response.getMetadata());
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
        metadata.put(GRAPH_EXTRACTION_ADVISOR_METADATA_KEY, observation);
        int maxBytes = requireBatchExecutor().limits().getMaxObservationBytes();
        response.setMetadata(GraphRagCandidateExecution.boundObservation(metadata, maxBytes, objectMapper));
        return response;
    }

    private Map<String, Object> graphExtractionAdvisorObservation(boolean enabled, boolean called, String status,
            String reason, GraphRagExtractionAdvice advice, GraphExtractionValidation validation, String errorMessage,
            int contextChunkCount) {
        Map<String, Object> observation = metadata("strategy", GRAPH_EXTRACTION_STRATEGY_LLM, "enabled", enabled,
                "called", called, "status", status, "reason", limit(reason, 300), "contextChunkCount",
                contextChunkCount);
        if (StrUtil.isNotBlank(errorMessage)) {
            observation.put("errorMessage", limit(errorMessage, 300));
        }
        if (advice != null) {
            observation.put("graphable", Boolean.TRUE.equals(advice.getGraphable()));
            observation.put("adviceConfidence", rounded(bounded(toDouble(advice.getConfidence(), 0D))));
            observation.put("adviceEntityCount", size(advice.getEntities()));
            observation.put("adviceRelationCount", size(advice.getRelations()));
            observation.put("adviceEvidenceCount", size(advice.getEvidences()));
        }
        if (validation != null) {
            observation.put("acceptedEntityCount", size(validation.entities()));
            observation.put("acceptedRelationCount", size(validation.relations()));
            observation.put("acceptedEvidenceCount", size(validation.evidences()));
            Map<String, Long> mappingStatusCounts = relationTypeMappingStatusCounts(validation.relations());
            if (!mappingStatusCounts.isEmpty()) {
                observation.put("relationTypeMappingStatusCounts", mappingStatusCounts);
                observation.put("downgradedRelationCount",
                        mappingStatusCounts.entrySet().stream()
                                .filter(entry -> entry.getKey() != null && entry.getKey().startsWith("downgraded"))
                                .mapToLong(Map.Entry::getValue).sum());
            }
            observation.put("accepted", validation.enhanced());
            if (StrUtil.isNotBlank(validation.rejectedReason())) {
                observation.put("rejectedReason", validation.rejectedReason());
            }
            if (validation.confidence() > 0D) {
                observation.put("acceptedConfidence", rounded(validation.confidence()));
            }
            if (CollUtil.isNotEmpty(validation.rejectedEntityReasons())) {
                observation.put("rejectedEntityCount", validation.rejectedEntityReasons().size());
                observation.put("rejectedEntityReasons", validation.rejectedEntityReasons());
            }
            if (validation.rejectedEvidenceCount() > 0) {
                observation.put("rejectedEvidenceCount", validation.rejectedEvidenceCount());
            }
            if (CollUtil.isNotEmpty(validation.rejectedEvidenceReasons())) {
                observation.put("rejectedEvidenceReasons", validation.rejectedEvidenceReasons());
            }
            if (CollUtil.isNotEmpty(validation.rejectedRelationReasons())) {
                observation.put("rejectedRelationCount", validation.rejectedRelationReasons().size());
                observation.put("rejectedRelationReasons", validation.rejectedRelationReasons());
            }
            if (validation.rejectedRelationEvidenceCount() > 0) {
                observation.put("rejectedRelationEvidenceCount", validation.rejectedRelationEvidenceCount());
            }
            if (CollUtil.isNotEmpty(validation.rejectedRelationEvidenceReasons())) {
                observation.put("rejectedRelationEvidenceReasons", validation.rejectedRelationEvidenceReasons());
            }
        }
        return observation;
    }

    private Map<String, Long> relationTypeMappingStatusCounts(List<GraphRagExtractionResponse.Relation> relations) {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        for (GraphRagExtractionResponse.Relation relation : relations) {
            if (relation == null || relation.getMetadata() == null) {
                continue;
            }
            String status = String.valueOf(relation.getMetadata().getOrDefault("relationTypeMappingStatus", ""));
            if (StrUtil.isBlank(status)) {
                continue;
            }
            counts.merge(status, 1L, Long::sum);
        }
        return counts;
    }

    private GraphRagExtractionContext buildGraphExtractionContext(Long documentId, Long taskId,
            GraphRagExtractionRequest request) {
        List<GraphRagExtractionContext.ChunkItem> chunks = new ArrayList<>();
        for (GraphRagExtractionRequest.Chunk chunk : request.getChunks()) {
            if (chunk == null || chunk.getChunkId() == null || StrUtil.isBlank(chunk.getText())) {
                continue;
            }
            chunks.add(GraphRagExtractionContext.ChunkItem.builder().chunkId(chunk.getChunkId())
                    .parentBlockId(chunk.getParentBlockId()).chunkNo(chunk.getChunkNo()).chunkType(chunk.getChunkType())
                    .title(chunk.getTitle()).sectionPath(chunk.getSectionPath()).pageNo(chunk.getPageNo())
                    .pageRange(chunk.getPageRange()).bboxJson(chunk.getBboxJson()).text(chunk.getText())
                    .structuredTables(chunk.getStructuredTables() == null ? new ArrayList<>()
                            : new ArrayList<>(chunk.getStructuredTables()))
                    .build());
        }
        return GraphRagExtractionContext.builder().documentId(documentId).taskId(taskId).chunks(chunks).build();
    }

    private GraphExtractionValidation validateGraphExtractionAdvice(GraphRagExtractionAdvice advice,
            GraphRagExtractionContext context) {
        if (advice == null) {
            return GraphExtractionValidation.rejected("EMPTY_ADVICE");
        }
        double adviceConfidence = bounded(toDouble(advice.getConfidence(), 0D));

        Map<Long, GraphRagExtractionContext.ChunkItem> chunkById = new LinkedHashMap<>();
        for (GraphRagExtractionContext.ChunkItem chunk : context.getChunks()) {
            if (chunk != null && chunk.getChunkId() != null && StrUtil.isNotBlank(chunk.getText())) {
                chunkById.put(chunk.getChunkId(), chunk);
            }
        }

        Map<String, EntityExtractionCandidate> entityCandidates = new LinkedHashMap<>();
        List<String> rejectedEntityReasons = new ArrayList<>();
        int adviceEntityIndex = 0;
        if (CollUtil.isNotEmpty(advice.getEntities())) {
            for (GraphRagExtractionAdvice.EntityItem entity : advice.getEntities()) {
                adviceEntityIndex++;
                EntityCandidateValidation entityValidation = validateGraphExtractionEntity(entity, chunkById);
                if (!entityValidation.valid()) {
                    rejectedEntityReasons.add(
                            advisorEntityRejectionReason(entityValidation.rejectedReason(), entity, adviceEntityIndex));
                    continue;
                }
                EntityExtractionCandidate candidate = entityValidation.candidate();
                if (entityCandidates.putIfAbsent(candidate.localEntityId(), candidate) != null) {
                    rejectedEntityReasons
                            .add(advisorEntityRejectionReason("ENTITY_ID_DUPLICATE", entity, adviceEntityIndex));
                }
            }
        }

        Map<String, RelationExtractionCandidate> relationCandidates = new LinkedHashMap<>();
        Map<Long, StructuredTableGrounding> structuredTables = structuredTableIndex(context);
        List<String> rejectedRelationReasons = new ArrayList<>();
        Set<String> rejectedRelationIds = new LinkedHashSet<>();
        int adviceRelationIndex = 0;
        if (CollUtil.isNotEmpty(advice.getRelations())) {
            for (GraphRagExtractionAdvice.RelationItem relation : advice.getRelations()) {
                adviceRelationIndex++;
                RelationCandidateValidation relationValidation = validateGraphExtractionRelation(relation,
                        entityCandidates, structuredTables);
                if (!relationValidation.valid()) {
                    rejectedRelationReasons.add(advisorRelationSchemaRejectionReason(
                            relationValidation.rejectedReason(), relation, adviceRelationIndex));
                    if (relation != null && StrUtil.isNotBlank(relation.getId())
                            && !relationCandidates.containsKey(relation.getId())) {
                        rejectedRelationIds.add(relation.getId());
                    }
                    continue;
                }
                RelationExtractionCandidate candidate = relationValidation.candidate();
                if (relationCandidates.putIfAbsent(candidate.localRelationId(), candidate) != null) {
                    rejectedRelationReasons.add(advisorRelationSchemaRejectionReason("RELATION_ID_DUPLICATE", relation,
                            adviceRelationIndex));
                    continue;
                }
                rejectedRelationIds.remove(candidate.localRelationId());
            }
        }

        Map<String, List<GraphRagExtractionAdvice.EvidenceItem>> entityEvidenceMap = new LinkedHashMap<>();
        Map<String, List<GraphRagExtractionAdvice.EvidenceItem>> relationEvidenceMap = new LinkedHashMap<>();
        List<String> rejectedEvidenceReasons = new ArrayList<>();
        List<String> rejectedRelationEvidenceReasons = new ArrayList<>();
        int acceptedEvidenceCount = 0;
        int rejectedEvidenceCount = 0;
        int rejectedRelationEvidenceCount = 0;
        int adviceEvidenceIndex = 0;
        if (CollUtil.isNotEmpty(advice.getEvidences())) {
            for (GraphRagExtractionAdvice.EvidenceItem evidence : advice.getEvidences()) {
                adviceEvidenceIndex++;
                if (evidence == null) {
                    rejectedEvidenceCount++;
                    rejectedEvidenceReasons.add(advisorEvidenceRejectionReason(
                            validateGraphExtractionEvidence(null, chunkById), null, adviceEvidenceIndex));
                    continue;
                }
                String entityId = StrUtil.blankToDefault(evidence.getEntityId(), "");
                String relationId = StrUtil.blankToDefault(evidence.getRelationId(), "");
                if (StrUtil.isNotBlank(relationId) && rejectedRelationIds.contains(relationId)) {
                    rejectedRelationEvidenceCount++;
                    AdvisorEvidenceValidation evidenceValidation = validateGraphExtractionEvidence(evidence, chunkById);
                    if (!evidenceValidation.valid()) {
                        rejectedRelationEvidenceReasons
                                .add(advisorEvidenceRejectionReason(evidenceValidation, evidence, adviceEvidenceIndex));
                    }
                    continue;
                }
                boolean entityOwner = entityCandidates.containsKey(entityId);
                boolean relationOwner = relationCandidates.containsKey(relationId);
                if (entityOwner == relationOwner) {
                    rejectedEvidenceCount++;
                    rejectedEvidenceReasons.add(advisorEvidenceOwnerRejectionReason(evidence, adviceEvidenceIndex));
                    continue;
                }
                AdvisorEvidenceValidation evidenceValidation = validateGraphExtractionEvidence(evidence, chunkById);
                if (!evidenceValidation.valid()) {
                    if (relationOwner) {
                        rejectedRelationEvidenceCount++;
                        rejectedRelationEvidenceReasons
                                .add(advisorEvidenceRejectionReason(evidenceValidation, evidence, adviceEvidenceIndex));
                        continue;
                    }
                    rejectedEvidenceCount++;
                    rejectedEvidenceReasons
                            .add(advisorEvidenceRejectionReason(evidenceValidation, evidence, adviceEvidenceIndex));
                    continue;
                }
                if (entityOwner) {
                    EntityExtractionCandidate owner = entityCandidates.get(entityId);
                    if (!candidateMentionedInText(owner, normalizeKey(evidence.getQuoteText()))) {
                        rejectedEvidenceCount++;
                        rejectedEvidenceReasons
                                .add("ENTITY_EVIDENCE_MENTION_MISSING:" + diagnosticToken(evidence.getId()));
                        continue;
                    }
                    entityEvidenceMap.computeIfAbsent(entityId, ignored -> new ArrayList<>()).add(evidence);
                }
                if (relationOwner) {
                    RelationEvidenceValidation relationEvidenceValidation = validateRelationEvidenceGrounding(evidence,
                            relationCandidates.get(relationId), entityCandidates);
                    if (!relationEvidenceValidation.valid()) {
                        rejectedRelationEvidenceCount++;
                        rejectedRelationEvidenceReasons.add(advisorRelationEvidenceRejectionReason(
                                relationEvidenceValidation, evidence, adviceEvidenceIndex));
                        continue;
                    }
                    relationEvidenceMap.computeIfAbsent(relationId, ignored -> new ArrayList<>()).add(evidence);
                }
                acceptedEvidenceCount++;
            }
        }

        List<String> ungroundedRelationIds = relationCandidates.values().stream()
                .map(RelationExtractionCandidate::localRelationId)
                .filter(relationId -> CollUtil.isEmpty(relationEvidenceMap.get(relationId))).toList();
        for (String relationId : ungroundedRelationIds) {
            RelationExtractionCandidate rejectedRelation = relationCandidates.remove(relationId);
            rejectedRelationIds.add(relationId);
            rejectedRelationReasons.add(advisorRelationWithoutGroundedEvidenceReason(rejectedRelation));
        }

        Set<String> relationGroundedEntityIds = new LinkedHashSet<>();
        for (String localRelationId : relationEvidenceMap.keySet()) {
            RelationExtractionCandidate relation = relationCandidates.get(localRelationId);
            if (relation != null) {
                relationGroundedEntityIds.add(relation.sourceEntityId());
                relationGroundedEntityIds.add(relation.targetEntityId());
            }
        }
        Set<String> groundedEntityIds = new LinkedHashSet<>(entityEvidenceMap.keySet());
        groundedEntityIds.addAll(relationGroundedEntityIds);
        entityCandidates.keySet().removeIf(entityId -> !groundedEntityIds.contains(entityId));
        entityEvidenceMap.keySet().retainAll(entityCandidates.keySet());
        if (entityCandidates.isEmpty()) {
            return GraphExtractionValidation.filtered(
                    firstCandidateRejectionReason(rejectedEntityReasons, rejectedEvidenceReasons,
                            rejectedRelationReasons, rejectedRelationEvidenceReasons, "NO_GROUNDED_ENTITY"),
                    adviceConfidence, rejectedEntityReasons, rejectedEvidenceCount, rejectedEvidenceReasons,
                    rejectedRelationReasons, rejectedRelationEvidenceCount, rejectedRelationEvidenceReasons);
        }

        Map<String, String> localEntityIdToGlobalId = new LinkedHashMap<>();
        List<GraphRagExtractionResponse.Entity> entities = new ArrayList<>();
        int entityIndex = 1;
        for (EntityExtractionCandidate candidate : entityCandidates.values()) {
            String sourceEntityId = generatedGraphExtractionId("LLM_ENT", candidate.localEntityId(),
                    localEntityIdToGlobalId, entityIndex++);
            localEntityIdToGlobalId.put(candidate.localEntityId(), sourceEntityId);
            GraphRagExtractionResponse.Entity entity = new GraphRagExtractionResponse.Entity();
            entity.setId(sourceEntityId);
            entity.setName(candidate.name());
            entity.setNormalizedName(candidate.normalizedName());
            entity.setAliases(new ArrayList<>(candidate.aliases()));
            entity.setType(candidate.entityType());
            entity.setDescription(candidate.description());
            entity.setConfidence(candidate.confidence());
            entity.setSourceChunkIds(new ArrayList<>(candidate.sourceChunkIds()));
            entity.setEvidenceIds(new ArrayList<>());
            entity.setMetadata(metadata("sourceType", GRAPH_EXTRACTION_STRATEGY_LLM, "sourceEntityId",
                    candidate.localEntityId(), "sourceChunkIds", candidate.sourceChunkIds(), "confidence",
                    candidate.confidence(), "reason", candidate.reason()));
            entities.add(entity);
        }

        Map<String, String> localRelationIdToGlobalId = new LinkedHashMap<>();
        List<GraphRagExtractionResponse.Relation> relations = new ArrayList<>();
        int relationIndex = 1;
        for (RelationExtractionCandidate candidate : relationCandidates.values()) {
            String sourceEntityId = localEntityIdToGlobalId.get(candidate.sourceEntityId());
            String targetEntityId = localEntityIdToGlobalId.get(candidate.targetEntityId());
            if (StrUtil.isBlank(sourceEntityId) || StrUtil.isBlank(targetEntityId)
                    || Objects.equals(sourceEntityId, targetEntityId)) {
                continue;
            }
            String relationId = generatedGraphExtractionId("LLM_REL", candidate.localRelationId(),
                    localRelationIdToGlobalId, relationIndex++);
            localRelationIdToGlobalId.put(candidate.localRelationId(), relationId);
            GraphRagExtractionResponse.Relation relation = new GraphRagExtractionResponse.Relation();
            relation.setId(relationId);
            relation.setSourceEntityId(sourceEntityId);
            relation.setTargetEntityId(targetEntityId);
            relation.setRelationType(candidate.relationType());
            relation.setDescription(candidate.description());
            relation.setWeight(candidate.weight());
            relation.setConfidence(candidate.confidence());
            relation.setEvidenceIds(new ArrayList<>());
            relation.setMetadata(metadata("sourceType", GRAPH_EXTRACTION_STRATEGY_LLM, "sourceRelationId",
                    candidate.localRelationId(), "sourceEntityIds",
                    List.of(candidate.sourceEntityId(), candidate.targetEntityId()), "requestedRelationType",
                    candidate.requestedRelationType(), "effectiveRelationType", candidate.relationType(), "supportMode",
                    candidate.supportMode(), "predicateQuoteText", candidate.predicateQuoteText(), "relationTypeReason",
                    candidate.relationTypeReason(), "relationTypeMappingStatus", candidate.relationTypeMappingStatus(),
                    "relationTypeMappingReason", candidate.relationTypeMappingReason(), "relationFactAuthority",
                    GraphRagRelationAuthority.factAuthority(candidate.supportMode()), "relationTypeAuthority",
                    GraphRagRelationAuthority.TYPE_AUTHORITY_LABEL_ONLY, "tableId", candidate.tableId(), "rowNo",
                    candidate.rowNo(), "sourceColumnNo", candidate.sourceColumnNo(), "targetColumnNo",
                    candidate.targetColumnNo(), "confidence", candidate.confidence()));
            relations.add(relation);
        }

        List<GraphRagExtractionResponse.Evidence> evidences = new ArrayList<>();
        int evidenceIndex = 1;
        for (Map.Entry<String, List<GraphRagExtractionAdvice.EvidenceItem>> entry : entityEvidenceMap.entrySet()) {
            String globalEntityId = localEntityIdToGlobalId.get(entry.getKey());
            if (StrUtil.isBlank(globalEntityId)) {
                continue;
            }
            for (GraphRagExtractionAdvice.EvidenceItem evidenceItem : entry.getValue()) {
                GraphRagExtractionResponse.Evidence evidence = buildGraphExtractionEvidence(evidenceItem,
                        globalEntityId, null, null, chunkById, evidenceIndex++);
                if (evidence != null) {
                    evidences.add(evidence);
                    addEvidenceIdToEntity(entities, globalEntityId, evidence.getId());
                }
            }
        }
        for (Map.Entry<String, List<GraphRagExtractionAdvice.EvidenceItem>> entry : relationEvidenceMap.entrySet()) {
            String globalRelationId = localRelationIdToGlobalId.get(entry.getKey());
            if (StrUtil.isBlank(globalRelationId)) {
                continue;
            }
            RelationExtractionCandidate relationCandidate = relationCandidates.get(entry.getKey());
            for (GraphRagExtractionAdvice.EvidenceItem evidenceItem : entry.getValue()) {
                GraphRagExtractionResponse.Evidence evidence = buildGraphExtractionEvidence(evidenceItem, null,
                        globalRelationId, relationCandidate, chunkById, evidenceIndex++);
                if (evidence != null) {
                    evidences.add(evidence);
                    addEvidenceIdToRelation(relations, globalRelationId, evidence.getId());
                }
            }
        }

        if (entities.isEmpty() || evidences.isEmpty()) {
            return GraphExtractionValidation.filtered(
                    firstCandidateRejectionReason(rejectedEntityReasons, rejectedEvidenceReasons,
                            rejectedRelationReasons, rejectedRelationEvidenceReasons, "NO_PERSISTABLE_GRAPH_ITEMS"),
                    adviceConfidence, rejectedEntityReasons, rejectedEvidenceCount, rejectedEvidenceReasons,
                    rejectedRelationReasons, rejectedRelationEvidenceCount, rejectedRelationEvidenceReasons);
        }
        return GraphExtractionValidation.accepted(entities, relations, evidences, adviceConfidence, advice.getReason(),
                rejectedEntityReasons, rejectedEvidenceCount, rejectedEvidenceReasons, rejectedRelationReasons,
                rejectedRelationEvidenceCount, rejectedRelationEvidenceReasons);
    }

    private EntityCandidateValidation validateGraphExtractionEntity(GraphRagExtractionAdvice.EntityItem entity,
            Map<Long, GraphRagExtractionContext.ChunkItem> chunkById) {
        if (entity == null) {
            return EntityCandidateValidation.rejected("ENTITY_MISSING");
        }
        if (StrUtil.isBlank(entity.getId())) {
            return EntityCandidateValidation.rejected("ENTITY_ID_MISSING");
        }
        if (StrUtil.isBlank(entity.getName())) {
            return EntityCandidateValidation.rejected("ENTITY_NAME_MISSING");
        }
        if (entity.getConfidence() == null || !Double.isFinite(entity.getConfidence()) || entity.getConfidence() < 0
                || entity.getConfidence() > 1) {
            return EntityCandidateValidation.rejected("ENTITY_CONFIDENCE_MISSING");
        }
        double confidence = bounded(toDouble(entity.getConfidence(), 0D));
        if (confidence < GRAPH_EXTRACTION_CONFIDENCE_THRESHOLD) {
            return EntityCandidateValidation.rejected("ENTITY_CONFIDENCE_BELOW_THRESHOLD");
        }
        List<Long> sourceChunkIds = new ArrayList<>();
        if (CollUtil.isNotEmpty(entity.getSourceChunkIds())) {
            for (Long chunkId : entity.getSourceChunkIds()) {
                GraphRagExtractionContext.ChunkItem chunk = chunkById.get(chunkId);
                if (chunk != null && isEntityGrounded(entity, chunk.getText())) {
                    sourceChunkIds.add(chunkId);
                }
            }
        }
        if (sourceChunkIds.isEmpty()) {
            return EntityCandidateValidation.rejected("ENTITY_SOURCE_NOT_GROUNDED");
        }
        String entityType = normalizeGraphType(entity.getEntityType());
        if (StrUtil.isBlank(entityType)) {
            entityType = "CONCEPT";
        }
        String normalizedName = limit(normalizeKey(entity.getName()), 500);
        return EntityCandidateValidation.accepted(new EntityExtractionCandidate(entity.getId(),
                limit(entity.getName(), 500), normalizedName, entityType,
                limitAliases(entity.getAliases(), entity.getName()).stream().filter(
                        alias -> sourceChunkIds.stream().anyMatch(id -> chunkById.get(id).getText().contains(alias)))
                        .toList(),
                limit(StrUtil.blankToDefault(entity.getDescription(), ""), 1000), rounded(confidence), sourceChunkIds,
                limit(entity.getId(), 64), "python.unified.candidates.v3"));
    }

    private RelationCandidateValidation validateGraphExtractionRelation(GraphRagExtractionAdvice.RelationItem relation,
            Map<String, EntityExtractionCandidate> entityCandidates,
            Map<Long, StructuredTableGrounding> structuredTables) {
        if (relation == null) {
            return RelationCandidateValidation.rejected("RELATION_MISSING");
        }
        if (StrUtil.isBlank(relation.getId())) {
            return RelationCandidateValidation.rejected("RELATION_ID_MISSING");
        }
        if (StrUtil.isBlank(relation.getSourceEntityId())) {
            return RelationCandidateValidation.rejected("RELATION_SOURCE_ENTITY_ID_MISSING");
        }
        if (StrUtil.isBlank(relation.getTargetEntityId())) {
            return RelationCandidateValidation.rejected("RELATION_TARGET_ENTITY_ID_MISSING");
        }
        if (relation.getConfidence() == null || !Double.isFinite(relation.getConfidence())
                || relation.getConfidence() < 0 || relation.getConfidence() > 1) {
            return RelationCandidateValidation.rejected("RELATION_CONFIDENCE_MISSING");
        }
        if (!entityCandidates.containsKey(relation.getSourceEntityId())) {
            return RelationCandidateValidation.rejected("RELATION_SOURCE_ENDPOINT_UNKNOWN");
        }
        if (!entityCandidates.containsKey(relation.getTargetEntityId())) {
            return RelationCandidateValidation.rejected("RELATION_TARGET_ENDPOINT_UNKNOWN");
        }
        if (Objects.equals(relation.getSourceEntityId(), relation.getTargetEntityId())) {
            return RelationCandidateValidation.rejected("RELATION_ENDPOINT_SELF_LOOP");
        }
        double confidence = bounded(toDouble(relation.getConfidence(), 0D));
        if (confidence < GRAPH_EXTRACTION_CONFIDENCE_THRESHOLD) {
            return RelationCandidateValidation.rejected("RELATION_CONFIDENCE_BELOW_THRESHOLD");
        }
        String supportMode = normalizeGraphType(relation.getSupportMode());
        if (!GraphRagRelationAuthority.SUPPORT_MODE_EXPLICIT_ACTION.equals(supportMode)
                && !GraphRagRelationAuthority.SUPPORT_MODE_STRUCTURED_ROW.equals(supportMode)) {
            return RelationCandidateValidation.rejected("RELATION_EXPLICIT_ACTION_REQUIRED");
        }
        String requestedRelationType = normalizeGraphType(relation.getRelationType());
        String relationType = GraphRagRelationAuthority.effectiveStorageType(supportMode);
        String predicateQuoteText = StrUtil.blankToDefault(relation.getPredicateQuoteText(), "");
        String relationTypeReason = limit(StrUtil.blankToDefault(relation.getRelationTypeReason(), ""), 600);
        String mappingStatus = GraphRagRelationAuthority.mappingStatus(requestedRelationType);
        String mappingReason = GraphRagRelationAuthority.mappingReason(requestedRelationType);
        EntityExtractionCandidate source = entityCandidates.get(relation.getSourceEntityId());
        EntityExtractionCandidate target = entityCandidates.get(relation.getTargetEntityId());
        Set<String> sourceNames = new LinkedHashSet<>();
        sourceNames.add(normalizeKey(source.name()));
        source.aliases().forEach(alias -> sourceNames.add(normalizeKey(alias)));
        if (sourceNames.contains(normalizeKey(target.name()))
                || target.aliases().stream().map(this::normalizeKey).anyMatch(sourceNames::contains)) {
            return RelationCandidateValidation.rejected("RELATION_ALIAS_SELF_LOOP");
        }
        if (GraphRagRelationAuthority.SUPPORT_MODE_EXPLICIT_ACTION.equals(supportMode)) {
            if (!isPredicateQuoteTextMeaningful(predicateQuoteText, source, target)) {
                return RelationCandidateValidation.rejected("RELATION_PREDICATE_INVALID");
            }
        }
        StructuredRelationGrounding structured = null;
        if (GraphRagRelationAuthority.SUPPORT_MODE_STRUCTURED_ROW.equals(supportMode)) {
            structured = validateStructuredRelation(relation, source, target, predicateQuoteText, structuredTables);
            if (!structured.valid()) {
                return RelationCandidateValidation.rejected(structured.rejectedReason());
            }
        }
        String description = GraphRagRelationAuthority.groundedDescription(source.name(), predicateQuoteText,
                target.name(), relation.getDescription());
        double weight = GraphRagRelationAuthority.SUPPORT_MODE_STRUCTURED_ROW.equals(supportMode)
                ? Math.min(0.65D, bounded(toDouble(relation.getWeight(), 0.60D)))
                : bounded(toDouble(relation.getWeight(), Math.max(0.70D, confidence)));
        return RelationCandidateValidation.accepted(new RelationExtractionCandidate(relation.getId(),
                relation.getSourceEntityId(), relation.getTargetEntityId(), relationType, requestedRelationType,
                supportMode, predicateQuoteText, relationTypeReason, mappingStatus, mappingReason,
                limit(description, 1000), rounded(weight), rounded(confidence), relation.getTableId(),
                relation.getRowNo(), relation.getSourceColumnNo(), relation.getTargetColumnNo(),
                structured == null ? List.of() : structured.chunkIds()));
    }

    private StructuredRelationGrounding validateStructuredRelation(GraphRagExtractionAdvice.RelationItem relation,
            EntityExtractionCandidate source, EntityExtractionCandidate target, String predicateQuoteText,
            Map<Long, StructuredTableGrounding> structuredTables) {
        if (relation.getTableId() == null || relation.getRowNo() == null || relation.getSourceColumnNo() == null
                || relation.getTargetColumnNo() == null
                || Objects.equals(relation.getSourceColumnNo(), relation.getTargetColumnNo())) {
            return StructuredRelationGrounding.rejected("RELATION_STRUCTURED_COORDINATES_INVALID");
        }
        StructuredTableGrounding grounding = structuredTables.get(relation.getTableId());
        if (grounding == null || grounding.ambiguous()) {
            return StructuredRelationGrounding.rejected("RELATION_STRUCTURED_TABLE_UNKNOWN");
        }
        GraphRagExtractionRequest.StructuredTable table = grounding.table();
        if (table == null || CollUtil.isEmpty(table.getColumns()) || CollUtil.isEmpty(table.getRows())) {
            return StructuredRelationGrounding.rejected("RELATION_STRUCTURED_PROJECTION_INVALID");
        }
        GraphRagExtractionRequest.StructuredTableRow row = table.getRows().stream()
                .filter(Objects::nonNull).filter(item -> Objects.equals(item.getRowNo(), relation.getRowNo()))
                .findFirst().orElse(null);
        GraphRagExtractionRequest.StructuredTableColumn targetColumn = table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(item -> Objects.equals(item.getColumnNo(), relation.getTargetColumnNo())).findFirst()
                .orElse(null);
        List<GraphRagExtractionRequest.StructuredTableCell> rowCells = row == null || row.getCells() == null
                ? List.of() : row.getCells();
        GraphRagExtractionRequest.StructuredTableCell sourceCell = rowCells.stream().filter(Objects::nonNull)
                .filter(item -> Objects.equals(item.getColumnNo(), relation.getSourceColumnNo())).findFirst()
                .orElse(null);
        GraphRagExtractionRequest.StructuredTableCell targetCell = rowCells.stream().filter(Objects::nonNull)
                .filter(item -> Objects.equals(item.getColumnNo(), relation.getTargetColumnNo())).findFirst()
                .orElse(null);
        if (targetColumn == null || sourceCell == null || targetCell == null) {
            return StructuredRelationGrounding.rejected("RELATION_STRUCTURED_CELL_UNKNOWN");
        }
        if (!candidateMatchesCell(source, sourceCell.getText()) || !candidateMatchesCell(target, targetCell.getText())) {
            return StructuredRelationGrounding.rejected("RELATION_STRUCTURED_ENDPOINT_MISMATCH");
        }
        if (!Objects.equals(normalizeKey(predicateQuoteText), normalizeKey(targetColumn.getColumnName()))) {
            return StructuredRelationGrounding.rejected("RELATION_STRUCTURED_PREDICATE_MISMATCH");
        }
        if (!structuredEndpointQuality(sourceCell.getText()) || !structuredEndpointQuality(targetCell.getText())) {
            return StructuredRelationGrounding.rejected("RELATION_STRUCTURED_ENDPOINT_QUALITY");
        }
        return StructuredRelationGrounding.accepted(List.copyOf(grounding.chunkIds()));
    }

    private boolean candidateMatchesCell(EntityExtractionCandidate candidate, String cellText) {
        String normalized = normalizeKey(cellText);
        return Objects.equals(normalizeKey(candidate.name()), normalized)
                || Objects.equals(normalizeKey(candidate.normalizedName()), normalized)
                || candidate.aliases().stream().anyMatch(alias -> Objects.equals(normalizeKey(alias), normalized));
    }

    private boolean structuredEndpointQuality(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String normalized = text.trim();
        int length = normalized.codePointCount(0, normalized.length());
        return length >= 2 && length <= 120 && !normalized.matches(".*[。！？.!?；;]\\s*$");
    }

    private Map<Long, StructuredTableGrounding> structuredTableIndex(GraphRagExtractionContext context) {
        Map<Long, StructuredTableGrounding> index = new LinkedHashMap<>();
        for (GraphRagExtractionContext.ChunkItem chunk : context == null || context.getChunks() == null
                ? List.<GraphRagExtractionContext.ChunkItem>of() : context.getChunks()) {
            if (chunk == null || chunk.getChunkId() == null) {
                continue;
            }
            for (GraphRagExtractionRequest.StructuredTable table : chunk.getStructuredTables() == null
                    ? List.<GraphRagExtractionRequest.StructuredTable>of() : chunk.getStructuredTables()) {
                if (table == null || table.getTableId() == null) {
                    continue;
                }
                StructuredTableGrounding existing = index.get(table.getTableId());
                if (existing == null) {
                    index.put(table.getTableId(), new StructuredTableGrounding(table,
                            new LinkedHashSet<>(List.of(chunk.getChunkId())), false));
                    continue;
                }
                existing.chunkIds().add(chunk.getChunkId());
                if (!Objects.equals(existing.table(), table)) {
                    index.put(table.getTableId(), new StructuredTableGrounding(existing.table(),
                            existing.chunkIds(), true));
                }
            }
        }
        return index;
    }

    private AdvisorEvidenceValidation validateGraphExtractionEvidence(GraphRagExtractionAdvice.EvidenceItem evidence,
            Map<Long, GraphRagExtractionContext.ChunkItem> chunkById) {
        if (evidence == null) {
            return AdvisorEvidenceValidation.invalid("EVIDENCE_MISSING");
        }
        if (StrUtil.isBlank(evidence.getId())) {
            return AdvisorEvidenceValidation.invalid("EVIDENCE_ID_MISSING");
        }
        if (evidence.getChunkId() == null) {
            return AdvisorEvidenceValidation.invalid("EVIDENCE_CHUNK_ID_MISSING");
        }
        if (StrUtil.isBlank(evidence.getQuoteText())) {
            return AdvisorEvidenceValidation.invalid("EVIDENCE_QUOTE_MISSING");
        }
        GraphRagExtractionContext.ChunkItem chunk = chunkById.get(evidence.getChunkId());
        if (chunk == null) {
            return AdvisorEvidenceValidation.invalid("EVIDENCE_CHUNK_NOT_IN_CONTEXT");
        }
        if (!isQuoteGrounded(evidence.getQuoteText(), chunk.getText())) {
            return AdvisorEvidenceValidation.invalid("EVIDENCE_QUOTE_NOT_GROUNDED");
        }
        if (evidence.getConfidence() == null || !Double.isFinite(evidence.getConfidence())
                || evidence.getConfidence() < 0 || evidence.getConfidence() > 1) {
            return AdvisorEvidenceValidation.invalid("EVIDENCE_CONFIDENCE_MISSING");
        }
        double confidence = bounded(evidence.getConfidence());
        if (confidence < GRAPH_EXTRACTION_CONFIDENCE_THRESHOLD) {
            return AdvisorEvidenceValidation.invalid("EVIDENCE_CONFIDENCE_BELOW_THRESHOLD(actual=" + rounded(confidence)
                    + ",minimum=" + rounded(GRAPH_EXTRACTION_CONFIDENCE_THRESHOLD) + ")");
        }
        return AdvisorEvidenceValidation.accepted();
    }

    private String firstCandidateRejectionReason(List<String> entityReasons, List<String> evidenceReasons,
            List<String> relationReasons, List<String> relationEvidenceReasons, String fallback) {
        if (CollUtil.isNotEmpty(relationEvidenceReasons)) {
            return relationEvidenceReasons.get(0);
        }
        if (CollUtil.isNotEmpty(evidenceReasons)) {
            return evidenceReasons.get(0);
        }
        if (CollUtil.isNotEmpty(relationReasons)) {
            return relationReasons.get(0);
        }
        if (CollUtil.isNotEmpty(entityReasons)) {
            return entityReasons.get(0);
        }
        return fallback;
    }

    private String advisorEntityRejectionReason(String rejectionReason, GraphRagExtractionAdvice.EntityItem entity,
            int entityIndex) {
        String entityId = entity == null ? "missing" : diagnosticToken(entity.getId());
        return "INVALID_ENTITY_SCHEMA_OR_GROUNDING:" + rejectionReason + "[entityIndex=" + entityIndex + ",entityId="
                + entityId + "]";
    }

    private String advisorEvidenceOwnerRejectionReason(GraphRagExtractionAdvice.EvidenceItem evidence,
            int evidenceIndex) {
        String evidenceId = evidence == null ? "missing" : diagnosticToken(evidence.getId());
        String entityId = evidence == null ? "missing" : diagnosticToken(evidence.getEntityId());
        String relationId = evidence == null ? "missing" : diagnosticToken(evidence.getRelationId());
        return "INVALID_EVIDENCE_OWNER:EVIDENCE_OWNER_INVALID" + "[evidenceIndex=" + evidenceIndex + ",evidenceId="
                + evidenceId + ",entityId=" + entityId + ",relationId=" + relationId + "]";
    }

    private String advisorEvidenceRejectionReason(AdvisorEvidenceValidation validation,
            GraphRagExtractionAdvice.EvidenceItem evidence, int evidenceIndex) {
        String evidenceId = evidence == null ? "missing" : diagnosticToken(evidence.getId());
        String chunkId = evidence == null || evidence.getChunkId() == null ? "missing"
                : String.valueOf(evidence.getChunkId());
        return "INVALID_EVIDENCE_SCHEMA_OR_QUOTE:" + validation.reason() + "[evidenceIndex=" + evidenceIndex
                + ",evidenceId=" + evidenceId + ",chunkId=" + chunkId + "]";
    }

    private String advisorRelationEvidenceRejectionReason(RelationEvidenceValidation validation,
            GraphRagExtractionAdvice.EvidenceItem evidence, int evidenceIndex) {
        String evidenceId = evidence == null ? "missing" : diagnosticToken(evidence.getId());
        String relationId = evidence == null ? "missing" : diagnosticToken(evidence.getRelationId());
        String chunkId = evidence == null || evidence.getChunkId() == null ? "missing"
                : String.valueOf(evidence.getChunkId());
        return "INVALID_RELATION_QUOTE:" + validation.reason() + "[evidenceIndex=" + evidenceIndex + ",evidenceId="
                + evidenceId + ",relationId=" + relationId + ",chunkId=" + chunkId + "]";
    }

    private String advisorRelationSchemaRejectionReason(String rejectionReason,
            GraphRagExtractionAdvice.RelationItem relation, int relationIndex) {
        String relationId = relation == null ? "missing" : diagnosticToken(relation.getId());
        String sourceEntityId = relation == null ? "missing" : diagnosticToken(relation.getSourceEntityId());
        String targetEntityId = relation == null ? "missing" : diagnosticToken(relation.getTargetEntityId());
        return "INVALID_RELATION_SCHEMA_OR_ENDPOINT:" + rejectionReason + "[relationIndex=" + relationIndex
                + ",relationId=" + relationId + ",sourceEntityId=" + sourceEntityId + ",targetEntityId="
                + targetEntityId + "]";
    }

    private String advisorRelationWithoutGroundedEvidenceReason(RelationExtractionCandidate relation) {
        String relationId = relation == null ? "missing" : diagnosticToken(relation.localRelationId());
        return "INVALID_RELATION_QUOTE:NO_GROUNDED_RELATION_EVIDENCE[relationId=" + relationId + "]";
    }

    private String diagnosticToken(String value) {
        String normalized = StrUtil.blankToDefault(value, "missing").replaceAll("[^A-Za-z0-9_.:-]", "_");
        return limit(normalized, 64);
    }

    private GraphRagExtractionResponse.Evidence buildGraphExtractionEvidence(
            GraphRagExtractionAdvice.EvidenceItem evidenceItem, String entityId, String relationId,
            RelationExtractionCandidate relation, Map<Long, GraphRagExtractionContext.ChunkItem> chunkById, int index) {
        if (evidenceItem == null || StrUtil.isBlank(evidenceItem.getId()) || evidenceItem.getChunkId() == null
                || StrUtil.isBlank(evidenceItem.getQuoteText())) {
            return null;
        }
        GraphRagExtractionContext.ChunkItem chunk = chunkById.get(evidenceItem.getChunkId());
        if (chunk == null || !isQuoteGrounded(evidenceItem.getQuoteText(), chunk.getText())) {
            return null;
        }
        if (StrUtil.isBlank(entityId) && StrUtil.isBlank(relationId)) {
            return null;
        }
        GraphRagExtractionResponse.Evidence evidence = new GraphRagExtractionResponse.Evidence();
        evidence.setId(generatedGraphExtractionEvidenceId(evidenceItem.getId(), index));
        evidence.setEntityId(entityId);
        evidence.setRelationId(relationId);
        evidence.setChunkId(chunk.getChunkId());
        evidence.setParentBlockId(chunk.getParentBlockId());
        evidence.setQuoteText(evidenceItem.getQuoteText());
        evidence.setBboxJson(chunk.getBboxJson());
        evidence.setPageNo(chunk.getPageNo());
        evidence.setPageRange(limit(chunk.getPageRange(), 64));
        evidence.setSectionPath(limit(chunk.getSectionPath(), 1000));
        evidence.setMetadata(metadata("sourceType", GRAPH_EXTRACTION_STRATEGY_LLM, "sourceEvidenceId",
                evidenceItem.getId(), "sourceChunkId", chunk.getChunkId(), "sourceSpan", evidenceItem.getMetadata(),
                "requestedRelationType", relation == null ? null : relation.requestedRelationType(),
                "effectiveRelationType", relation == null ? null : relation.relationType(), "supportMode",
                relation == null ? null : relation.supportMode(), "predicateQuoteText",
                relation == null ? null : relation.predicateQuoteText(), "relationTypeReason",
                relation == null ? null : relation.relationTypeReason(), "relationTypeMappingStatus",
                relation == null ? null : relation.relationTypeMappingStatus(), "relationTypeMappingReason",
                relation == null ? null : relation.relationTypeMappingReason(), "relationFactAuthority",
                relation == null ? null : GraphRagRelationAuthority.factAuthority(relation.supportMode()),
                "relationTypeAuthority",
                relation == null ? null : GraphRagRelationAuthority.TYPE_AUTHORITY_LABEL_ONLY, "confidence",
                rounded(bounded(toDouble(evidenceItem.getConfidence(), 0D)))));
        return evidence;
    }

    private void addEvidenceIdToEntity(List<GraphRagExtractionResponse.Entity> entities, String entityId,
            String evidenceId) {
        for (GraphRagExtractionResponse.Entity entity : entities) {
            if (entity != null && Objects.equals(entity.getId(), entityId)) {
                if (entity.getEvidenceIds() == null) {
                    entity.setEvidenceIds(new ArrayList<>());
                }
                entity.getEvidenceIds().add(evidenceId);
                return;
            }
        }
    }

    private void addEvidenceIdToRelation(List<GraphRagExtractionResponse.Relation> relations, String relationId,
            String evidenceId) {
        for (GraphRagExtractionResponse.Relation relation : relations) {
            if (relation != null && Objects.equals(relation.getId(), relationId)) {
                if (relation.getEvidenceIds() == null) {
                    relation.setEvidenceIds(new ArrayList<>());
                }
                relation.getEvidenceIds().add(evidenceId);
                return;
            }
        }
    }

    private GraphRagExtractionResponse mergeGraphExtractionResponse(GraphRagExtractionResponse baseResponse,
            GraphExtractionValidation validation) {
        GraphRagExtractionResponse merged = new GraphRagExtractionResponse();
        merged.setEntities(mergeList(baseResponse.getEntities(), validation.entities()));
        merged.setRelations(mergeList(baseResponse.getRelations(), validation.relations()));
        merged.setEvidences(mergeList(baseResponse.getEvidences(), validation.evidences()));
        merged.setMetadata(baseResponse.getMetadata());
        return merged;
    }

    private <T> List<T> mergeList(List<T> baseValues, List<T> addedValues) {
        List<T> result = new ArrayList<>();
        if (CollUtil.isNotEmpty(baseValues)) {
            result.addAll(baseValues);
        }
        if (CollUtil.isNotEmpty(addedValues)) {
            result.addAll(addedValues);
        }
        return result;
    }

    private boolean isEntityGrounded(GraphRagExtractionAdvice.EntityItem entity, String chunkText) {
        if (entity == null || StrUtil.isBlank(chunkText)) {
            return false;
        }
        return StrUtil.isNotBlank(entity.getName()) && chunkText.contains(entity.getName());
    }

    private boolean isQuoteGrounded(String quoteText, String chunkText) {
        if (StrUtil.isBlank(quoteText) || StrUtil.isBlank(chunkText)) {
            return false;
        }
        return chunkText.contains(quoteText);
    }

    private RelationEvidenceValidation validateRelationEvidenceGrounding(GraphRagExtractionAdvice.EvidenceItem evidence,
            RelationExtractionCandidate relation, Map<String, EntityExtractionCandidate> entityCandidates) {
        if (evidence == null || relation == null || StrUtil.isBlank(evidence.getQuoteText())) {
            return RelationEvidenceValidation.invalid("RELATION_QUOTE_CONTEXT_INVALID");
        }
        EntityExtractionCandidate source = entityCandidates.get(relation.sourceEntityId());
        EntityExtractionCandidate target = entityCandidates.get(relation.targetEntityId());
        if (source == null) {
            return RelationEvidenceValidation.invalid("RELATION_QUOTE_SOURCE_ENTITY_MISSING");
        }
        if (target == null) {
            return RelationEvidenceValidation.invalid("RELATION_QUOTE_TARGET_ENTITY_MISSING");
        }
        if (GraphRagRelationAuthority.SUPPORT_MODE_STRUCTURED_ROW.equals(relation.supportMode())
                && !relation.structuredChunkIds().contains(evidence.getChunkId())) {
            return RelationEvidenceValidation.invalid("RELATION_QUOTE_STRUCTURED_TABLE_MISMATCH");
        }
        String normalizedQuote = normalizeKey(evidence.getQuoteText());
        if (!candidateMentionedInText(source, normalizedQuote)) {
            return RelationEvidenceValidation.invalid("RELATION_QUOTE_SOURCE_MENTION_MISSING");
        }
        if (!candidateMentionedInText(target, normalizedQuote)) {
            return RelationEvidenceValidation.invalid("RELATION_QUOTE_TARGET_MENTION_MISSING");
        }
        if (GraphRagRelationAuthority.SUPPORT_MODE_EXPLICIT_ACTION.equals(relation.supportMode())
                && !isPredicateQuoteGrounded(relation.predicateQuoteText(), normalizedQuote, source, target)) {
            return RelationEvidenceValidation.invalid("RELATION_QUOTE_PREDICATE_MISSING");
        }
        if (GraphRagRelationAuthority.SUPPORT_MODE_EXPLICIT_ACTION.equals(relation.supportMode())
                && !isDirectActionGroundedInOneSegment(evidence.getQuoteText(), relation, source, target)) {
            return RelationEvidenceValidation.invalid("RELATION_QUOTE_DIRECT_ACTION_NOT_GROUNDED");
        }
        return RelationEvidenceValidation.accepted();
    }

    private boolean isDirectActionGroundedInOneSegment(String quoteText, RelationExtractionCandidate relation,
            EntityExtractionCandidate source, EntityExtractionCandidate target) {
        if (StrUtil.isBlank(quoteText)) {
            return false;
        }
        for (String segment : DIRECT_ACTION_SEGMENT_BOUNDARY.split(quoteText)) {
            String normalizedSegment = normalizeKey(segment);
            if (candidateMentionedInText(source, normalizedSegment)
                    && candidateMentionedInText(target, normalizedSegment)
                    && isPredicateQuoteGrounded(relation.predicateQuoteText(), normalizedSegment, source, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean candidateMentionedInText(EntityExtractionCandidate candidate, String normalizedText) {
        if (candidate == null || StrUtil.isBlank(normalizedText)) {
            return false;
        }
        if (normalizedText.contains(normalizeKey(candidate.name()))) {
            return true;
        }
        if (normalizedText.contains(normalizeKey(candidate.normalizedName()))) {
            return true;
        }
        if (CollUtil.isNotEmpty(candidate.aliases())) {
            for (String alias : candidate.aliases()) {
                if (StrUtil.isNotBlank(alias) && normalizedText.contains(normalizeKey(alias))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPredicateQuoteGrounded(String predicateQuoteText, String normalizedQuote,
            EntityExtractionCandidate source, EntityExtractionCandidate target) {
        if (!isPredicateQuoteTextMeaningful(predicateQuoteText, source, target)) {
            return false;
        }
        return normalizedQuote.contains(normalizeKey(predicateQuoteText));
    }

    private boolean isPredicateQuoteTextMeaningful(String predicateQuoteText, EntityExtractionCandidate source,
            EntityExtractionCandidate target) {
        String normalized = normalizeKey(predicateQuoteText);
        if (normalized.length() < 2) {
            return false;
        }
        String withoutEntityMentions = normalized;
        withoutEntityMentions = removeCandidateMentions(withoutEntityMentions, source);
        withoutEntityMentions = removeCandidateMentions(withoutEntityMentions, target);
        return withoutEntityMentions.length() >= 2;
    }

    private String removeCandidateMentions(String text, EntityExtractionCandidate candidate) {
        if (StrUtil.isBlank(text) || candidate == null) {
            return StrUtil.blankToDefault(text, "");
        }
        String result = removeNormalizedTerm(text, candidate.name());
        result = removeNormalizedTerm(result, candidate.normalizedName());
        if (CollUtil.isNotEmpty(candidate.aliases())) {
            for (String alias : candidate.aliases()) {
                result = removeNormalizedTerm(result, alias);
            }
        }
        return result;
    }

    private String removeNormalizedTerm(String text, String term) {
        String normalizedTerm = normalizeKey(term);
        if (StrUtil.isBlank(text) || StrUtil.isBlank(normalizedTerm)) {
            return StrUtil.blankToDefault(text, "");
        }
        return text.replace(normalizedTerm, "");
    }

    private String normalizeGraphType(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = value.trim().toUpperCase().replaceAll("[^A-Z0-9_]+", "_").replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return limit(normalized, 64);
    }

    private String generatedGraphExtractionId(String prefix, String localId, Map<String, String> existingIds,
            int index) {
        String candidate = prefix + "_" + limit(sanitizeGraphExtractionId(localId), 32);
        if (!existingIds.containsValue(candidate)) {
            return candidate;
        }
        return candidate + "_" + index;
    }

    private String generatedGraphExtractionEvidenceId(String localId, int index) {
        return "LLM_EV_" + index + "_" + limit(sanitizeGraphExtractionId(localId), 28);
    }

    private String sanitizeGraphExtractionId(String value) {
        if (StrUtil.isBlank(value)) {
            return "UNKNOWN";
        }
        return value.replaceAll("[^A-Za-z0-9_\\-]+", "_").replaceAll("_+", "_");
    }

    private boolean acquireLease(Long documentId, Long taskId, String leaseKey, String ownerToken, Duration leaseTtl,
            int maxAttempts) {
        if (!buildProperties.isLeaseEnabled()) {
            checkpointService.markRunning(documentId, taskId, "LOCK_SKIPPED", 0, maxAttempts,
                    metadata("leaseEnabled", false));
            return false;
        }
        boolean acquired = redisLeaseManager.acquire(leaseKey, ownerToken, leaseTtl);
        if (!acquired) {
            IllegalStateException exception = new IllegalStateException("GraphRAG 构建租约已被占用，请稍后重试。");
            checkpointService.markRejected(documentId, taskId, "LOCK_CONFLICT", maxAttempts, exception.getMessage(),
                    metadata("leaseKey", leaseKey));
            throw failure("FAILED_CONCURRENCY/NO_COMMIT: " + exception.getMessage(), exception, 0,
                    GraphRagBuildResult.InvocationOutcome.NOT_CALLED, GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                    Map.of("leaseKey", leaseKey));
        }
        checkpointService.markRunning(documentId, taskId, "LOCK_ACQUIRED", 0, maxAttempts,
                metadata("leaseKey", leaseKey, "leaseTtlSeconds", leaseTtl.getSeconds()));
        return true;
    }

    private GraphRagExtractionResponse executeCandidates(GraphRagExtractionRequest request, String leaseKey,
            String ownerToken, Duration leaseTtl) {
        Runnable lease = () -> {
            checkTaskActive(request.getTaskId());
            renewLeaseOrFail(leaseKey, ownerToken, leaseTtl);
        };
        Consumer<Map<String, Object>> progress = metadata -> {
            lease.run();
            checkpointService.markRunning(request.getDocumentId(), request.getTaskId(), "EXTRACTING", 1, 1,
                    Map.of("extractorMetadata", metadata));
            log.info(
                    "GraphRAG 抽取进度: documentId={}, taskId={}, status={}, batches={}/{}, failed={}, retry={}, sources={}/{}, costMillis={}, lastEvent={}, error={}",
                    request.getDocumentId(), request.getTaskId(), metadata.get("status"),
                    metadata.get("successfulBatchCount"), metadata.get("plannedBatchCount"),
                    metadata.get("failedBatchCount"), metadata.get("retryCount"), metadata.get("completedSourceCount"),
                    metadata.get("plannedSourceCount"), metadata.get("costMillis"), metadata.get("lastEvent"),
                    metadata.get("error"));
        };
        GraphRagBatchExecutor executor = requireBatchExecutor();
        if (1000L * 2 >= leaseTtl.toMillis()) {
            throw new IllegalArgumentException("GraphRAG lease TTL must exceed two execution heartbeat intervals");
        }
        return new GraphRagCandidateExecution(graphRagExtractionPort, objectMapper, executor).execute(request,
                lease, progress);
    }

    private GraphRagBatchExecutor requireBatchExecutor() {
        if (batchExecutor == null) {
            throw new IllegalStateException("GraphRAG batch executor is not configured");
        }
        return batchExecutor;
    }

    private void checkTaskActive(Long taskId) {
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (Thread.currentThread().isInterrupted() || task == null
                || !Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.RUNNING.getCode())) {
            throw new GraphRagBuildStoppedException(GraphRagBuildStoppedException.Reason.CANCELLED);
        }
    }

    private void renewLeaseOrFail(String leaseKey, String ownerToken, Duration leaseTtl) {
        if (!buildProperties.isLeaseEnabled()) {
            return;
        }
        boolean renewed = redisLeaseManager.renew(leaseKey, ownerToken, leaseTtl);
        if (!renewed) {
            throw new GraphRagBuildStoppedException(GraphRagBuildStoppedException.Reason.LEASE_LOST);
        }
    }

    private void releaseLease(boolean leaseAcquired, String leaseKey, String ownerToken, Long documentId, Long taskId) {
        if (!leaseAcquired || !buildProperties.isLeaseEnabled()) {
            return;
        }
        try {
            boolean released = redisLeaseManager.release(leaseKey, ownerToken);
            if (!released) {
                log.warn("GraphRAG 构建租约释放失败或已过期: documentId={}, taskId={}, leaseKey={}", documentId, taskId, leaseKey);
            }
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG 构建租约释放异常: documentId={}, taskId={}, leaseKey={}", documentId, taskId, leaseKey,
                    exception);
        }
    }

    private int maxAttempts() {
        return Math.max(1, buildProperties.getMaxAttempts());
    }

    private KnowledgeBaseIndexingOptions.GraphRagBuildOptions graphRagBuildOptions(Long documentId) {
        if (indexingConfigResolver == null) {
            throw new IllegalStateException("知识库 GraphRAG 配置提供方不可用");
        }
        return indexingConfigResolver.resolveByDocumentId(documentId).getGraphRag();
    }

    private Duration leaseTtl() {
        return Duration.ofSeconds(Math.max(1, buildProperties.getLeaseTtlSeconds()));
    }

    private long retryBackoffMillis(int attempt) {
        long baseBackoff = Math.max(0L, buildProperties.getRetryBackoffMillis());
        return baseBackoff * Math.max(1, attempt);
    }

    private void sleepBeforeRetry(Long documentId, Long taskId, String stage, int attempt, int maxAttempts,
            long backoffMillis) {
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            IllegalStateException interrupted = new IllegalStateException("GraphRAG 构建重试等待被中断。", exception);
            checkpointService.markFailure(documentId, taskId, "RETRY_INTERRUPTED", attempt, maxAttempts, interrupted);
            throw interrupted;
        }
    }

    private String leaseKey(Long taskId) {
        return "smartledge-agent:document:graph-rag:build:" + taskId;
    }

    private int size(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        deleteByTaskInternal(documentId, taskId);
        refreshCrossDocumentIndexAfterCommit(documentId, taskId);
    }

    private void deleteByTaskInternal(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        evidenceMapper.delete(new LambdaQueryWrapper<SuperAgentKgEvidence>()
                .eq(SuperAgentKgEvidence::getDocumentId, documentId).eq(SuperAgentKgEvidence::getTaskId, taskId));
        relationMapper.delete(new LambdaQueryWrapper<SuperAgentKgRelation>()
                .eq(SuperAgentKgRelation::getDocumentId, documentId).eq(SuperAgentKgRelation::getTaskId, taskId));
        entityMapper.delete(new LambdaQueryWrapper<SuperAgentKgEntity>()
                .eq(SuperAgentKgEntity::getDocumentId, documentId).eq(SuperAgentKgEntity::getTaskId, taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        evidenceMapper.delete(
                new LambdaQueryWrapper<SuperAgentKgEvidence>().eq(SuperAgentKgEvidence::getDocumentId, documentId));
        relationMapper.delete(
                new LambdaQueryWrapper<SuperAgentKgRelation>().eq(SuperAgentKgRelation::getDocumentId, documentId));
        entityMapper
                .delete(new LambdaQueryWrapper<SuperAgentKgEntity>().eq(SuperAgentKgEntity::getDocumentId, documentId));
        refreshCrossDocumentIndexAfterCommit(documentId);
    }

    private void refreshCrossDocumentIndexAfterCommit(Long documentId) {
        refreshCrossDocumentIndexAfterCommit(documentId, null);
    }

    private void refreshCrossDocumentIndexAfterCommit(Long documentId, Long taskId) {
        if (crossDocumentIndexService == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    rebuildCrossDocumentIndexAfterDelete(documentId, taskId);
                }
            });
            return;
        }
        rebuildCrossDocumentIndexAfterDelete(documentId, taskId);
    }

    private void rebuildCrossDocumentIndexAfterDelete(Long documentId, Long taskId) {
        try {
            crossDocumentIndexService.rebuildAll();
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG 删除后跨文档派生索引刷新失败，源 KG 删除已完成: documentId={}, taskId={}, message={}", documentId, taskId,
                    exception.getMessage());
        }
    }

    private GraphRagExtractionRequest buildRequest(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks) {
        GraphRagExtractionRequest request = new GraphRagExtractionRequest();
        request.setDocumentId(documentId);
        request.setTaskId(taskId);
        request.setSourceParseTaskId(taskMapper.selectById(taskId).getSourceParseTaskId());
        request.setPlanId(taskMapper.selectById(taskId).getPlanId());
        GraphRagExtractionOptions options = extractionConfiguration == null ? new GraphRagExtractionOptions()
                : extractionConfiguration.extraction();
        if (options == null) {
            throw failure("FAILED_CONFIG: GraphRAG extraction configuration is missing",
                    null, 0, GraphRagBuildResult.InvocationOutcome.NOT_CALLED,
                    GraphRagBuildResult.InvocationOutcome.NOT_CALLED, Map.of());
        }
        request.setOptions(objectMapper.convertValue(options, new TypeReference<Map<String, Object>>() {
        }));

        Map<Long, List<GraphRagExtractionRequest.StructuredTable>> structuredTables = tableProjectionPort == null
                ? Map.of()
                : tableProjectionPort.load(documentId, request.getSourceParseTaskId(), chunks);
        if (structuredTables == null) {
            structuredTables = Map.of();
        }

        List<GraphRagExtractionRequest.Chunk> requestChunks = new ArrayList<>();
        for (SuperAgentDocumentChunk chunk : chunks == null ? List.<SuperAgentDocumentChunk>of() : chunks) {
            if (chunk == null || chunk.getId() == null || StrUtil.isBlank(chunk.getChunkText())) {
                if (chunk != null && chunk.getId() != null) {
                    request.getExcludedBlankChunkIds().add(chunk.getId());
                }
                continue;
            }
            GraphRagExtractionRequest.Chunk requestChunk = new GraphRagExtractionRequest.Chunk();
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
            requestChunk
                    .setContentWithWeight(StrUtil.blankToDefault(chunk.getContentWithWeight(), chunk.getChunkText()));
            requestChunk.setSourceBlockIds(chunk.getSourceBlockIds());
            List<GraphRagExtractionRequest.StructuredTable> chunkTables = structuredTables.get(chunk.getId());
            requestChunk.setStructuredTables(chunkTables == null ? new ArrayList<>() : new ArrayList<>(chunkTables));
            requestChunk.setMetadata(chunkMetadata(chunk));
            requestChunks.add(requestChunk);
        }
        requestChunks.sort(Comparator.comparing(GraphRagExtractionRequest.Chunk::getChunkId));
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
        return metadata;
    }

    private SavedEntities prepareEntities(Long documentId, Long taskId,
            List<GraphRagExtractionResponse.Entity> entities) {
        Map<String, Long> entityIdMap = new LinkedHashMap<>();
        if (CollUtil.isEmpty(entities)) {
            return new SavedEntities(entityIdMap, new LinkedHashMap<>());
        }
        Map<String, EntityAccumulator> accumulators = new LinkedHashMap<>();
        Map<String, String> aliasIndex = new LinkedHashMap<>();
        Map<String, EntityResolutionDecision> entityResolutionDecisions = resolveEntityCanonicalKeyAdvice(entities);

        for (GraphRagExtractionResponse.Entity extracted : entities) {
            if (extracted == null || StrUtil.isBlank(extracted.getId()) || StrUtil.isBlank(extracted.getName())) {
                continue;
            }
            String sourceKey = canonicalEntityKey(extracted);
            EntityResolutionDecision resolutionDecision = entityResolutionDecisions.get(extracted.getId());
            String canonicalKey = resolutionDecision == null
                    ? resolveEntityCanonicalKey(extracted, aliasIndex, accumulators.keySet(), sourceKey)
                    : resolutionDecision.canonicalKey();
            EntityAccumulator accumulator = accumulators.computeIfAbsent(canonicalKey, EntityAccumulator::new);
            accumulator.merge(extracted);
            accumulator.applyResolutionAdvice(resolutionDecision);
            indexEntityAliases(aliasIndex, canonicalKey, accumulator);
        }

        Map<Long, SuperAgentKgEntity> entitiesById = new LinkedHashMap<>();
        for (EntityAccumulator accumulator : accumulators.values()) {
            Long entityId = uidGenerator.getUid();
            SuperAgentKgEntity entity = new SuperAgentKgEntity();
            entity.setId(entityId);
            entity.setDocumentId(documentId);
            entity.setTaskId(taskId);
            entity.setEntityKey(limit(accumulator.canonicalKey, 255));
            entity.setName(limit(accumulator.name, 500));
            entity.setNormalizedName(limit(accumulator.normalizedName, 500));
            entity.setEntityType(limit(accumulator.entityType, 64));
            entity.setDescription(limit(accumulator.description, 1000));
            entity.setMetadataJson(writeJson(metadata("canonicalKey", accumulator.canonicalKey, "sourceEntityIds",
                    accumulator.sourceEntityIds, "sourceChunkIds", accumulator.sourceChunkIds, "evidenceIds",
                    accumulator.evidenceIds, "aliases", accumulator.aliases, "sourceMetadata",
                    accumulator.sourceMetadata, "candidateSources", accumulator.candidateSources, "extractorSources",
                    accumulator.extractorSources, "mentionCount", accumulator.mentionCount, "candidateScore",
                    accumulator.candidateScore, "confidence", accumulator.confidence, "entityResolutionStrategy",
                    accumulator.entityResolutionEnhanced ? "llm.controlled.v1" : "java.alias.v1",
                    "entityResolutionEnhanced", accumulator.entityResolutionEnhanced, "entityResolutionConfidence",
                    accumulator.entityResolutionConfidence, "entityResolutionReason",
                    accumulator.entityResolutionReason, "entityResolutionCanonicalName",
                    accumulator.entityResolutionCanonicalName, "entityResolutionSourceEntityIds",
                    accumulator.entityResolutionSourceEntityIds)));
            entity.setStatus(BusinessStatus.YES.getCode());
            entitiesById.put(entityId, entity);
            for (String sourceEntityId : accumulator.sourceEntityIds) {
                entityIdMap.put(sourceEntityId, entityId);
            }
        }
        return new SavedEntities(entityIdMap, entitiesById);
    }

    private SavedRelations prepareRelations(Long documentId, Long taskId,
            List<GraphRagExtractionResponse.Relation> relations, Map<String, Long> entityIdMap) {
        Map<String, Long> relationIdMap = new LinkedHashMap<>();
        if (CollUtil.isEmpty(relations)) {
            return new SavedRelations(relationIdMap, new LinkedHashMap<>());
        }
        Map<String, RelationAccumulator> accumulators = new LinkedHashMap<>();
        Map<String, String> sourceRelationKeyMap = new LinkedHashMap<>();

        for (GraphRagExtractionResponse.Relation extracted : relations) {
            if (extracted == null || StrUtil.isBlank(extracted.getId())) {
                continue;
            }
            Long sourceEntityId = entityIdMap.get(extracted.getSourceEntityId());
            Long targetEntityId = entityIdMap.get(extracted.getTargetEntityId());
            if (sourceEntityId == null || targetEntityId == null) {
                log.warn("跳过缺少端点实体的 GraphRAG 关系: documentId={}, taskId={}, relationId={}, source={}, target={}",
                        documentId, taskId, extracted.getId(), extracted.getSourceEntityId(),
                        extracted.getTargetEntityId());
                continue;
            }
            if (Objects.equals(sourceEntityId, targetEntityId)) {
                continue;
            }
            String relationType = limit(StrUtil.blankToDefault(extracted.getRelationType(), "ASSOCIATED_WITH"), 64);
            String canonicalKey = canonicalRelationKey(sourceEntityId, targetEntityId, relationType);
            RelationAccumulator accumulator = accumulators.computeIfAbsent(canonicalKey,
                    key -> new RelationAccumulator(key, sourceEntityId, targetEntityId, relationType));
            accumulator.merge(extracted);
            sourceRelationKeyMap.put(extracted.getId(), canonicalKey);
        }

        Map<Long, SuperAgentKgRelation> relationsById = new LinkedHashMap<>();
        for (RelationAccumulator accumulator : accumulators.values()) {
            Long relationId = uidGenerator.getUid();
            SuperAgentKgRelation relation = new SuperAgentKgRelation();
            relation.setId(relationId);
            relation.setDocumentId(documentId);
            relation.setTaskId(taskId);
            relation.setSourceEntityId(accumulator.sourceEntityId);
            relation.setTargetEntityId(accumulator.targetEntityId);
            relation.setRelationType(limit(accumulator.relationType, 64));
            relation.setDescription(limit(accumulator.description, 1000));
            relation.setWeight(weight(accumulator.weight));
            relation.setMetadataJson(writeJson(metadata("canonicalKey", accumulator.canonicalKey, "sourceRelationIds",
                    accumulator.sourceRelationIds, "sourceEntityIds", accumulator.sourceEntityIds, "targetEntityIds",
                    accumulator.targetEntityIds, "evidenceIds", accumulator.evidenceIds, "sourceMetadata",
                    accumulator.sourceMetadata, "groundedPredicateTexts", accumulator.groundedPredicateTexts,
                    "relationFactAuthority", accumulator.factAuthority(),
                    "relationFactAuthorities", accumulator.relationFactAuthorities,
                    "relationTypeAuthority", GraphRagRelationAuthority.TYPE_AUTHORITY_LABEL_ONLY, "candidateSources",
                    accumulator.candidateSources, "extractorSources", accumulator.extractorSources, "confidence",
                    accumulator.confidence)));
            relation.setStatus(BusinessStatus.YES.getCode());
            relationsById.put(relationId, relation);
            for (String sourceRelationId : accumulator.sourceRelationIds) {
                relationIdMap.put(sourceRelationId, relationId);
            }
        }
        return new SavedRelations(relationIdMap, relationsById);
    }

    private void insertEntities(Collection<SuperAgentKgEntity> entities) {
        if (CollUtil.isEmpty(entities)) {
            return;
        }
        for (SuperAgentKgEntity entity : entities) {
            entityMapper.insert(entity);
        }
    }

    private void insertRelations(Collection<SuperAgentKgRelation> relations) {
        if (CollUtil.isEmpty(relations)) {
            return;
        }
        for (SuperAgentKgRelation relation : relations) {
            relationMapper.insert(relation);
        }
    }

    private GraphRankSnapshot enrichGraphRankMetadata(Map<Long, SuperAgentKgEntity> entitiesById,
            Map<Long, SuperAgentKgRelation> relationsById) {
        Map<Long, NodeRank> entityRanks = calculateEntityRanks(entitiesById, relationsById);
        double maxRelationWeight = relationsById.values().stream().mapToDouble(this::relationWeightValue).max()
                .orElse(0D);
        Map<Long, RelationRank> relationRanks = new LinkedHashMap<>();

        for (SuperAgentKgEntity entity : entitiesById.values()) {
            NodeRank rank = entityRanks.getOrDefault(entity.getId(), NodeRank.empty());
            Map<String, Object> metadata = readMetadata(entity.getMetadataJson());
            metadata.put("rankAlgorithm", RANK_ALGORITHM);
            metadata.put("pagerank", rounded(rank.pagerank()));
            metadata.put("rankBoost", rounded(rank.rankBoost()));
            metadata.put("rankPosition", rank.rankPosition());
            metadata.put("degree", rank.degree());
            metadata.put("inDegree", rank.inDegree());
            metadata.put("outDegree", rank.outDegree());
            metadata.put("weightedDegree", rounded(rank.weightedDegree()));
            entity.setMetadataJson(writeJson(metadata));
        }

        for (SuperAgentKgRelation relation : relationsById.values()) {
            NodeRank sourceRank = entityRanks.getOrDefault(relation.getSourceEntityId(), NodeRank.empty());
            NodeRank targetRank = entityRanks.getOrDefault(relation.getTargetEntityId(), NodeRank.empty());
            double relationWeightBoost = maxRelationWeight <= 0D ? 0D
                    : relationWeightValue(relation) / maxRelationWeight;
            double relationRankBoost = bounded(Math.max(sourceRank.rankBoost(), targetRank.rankBoost()) * 0.55D
                    + ((sourceRank.rankBoost() + targetRank.rankBoost()) / 2D) * 0.30D + relationWeightBoost * 0.15D);
            RelationRank relationRank = new RelationRank(relationRankBoost, sourceRank.rankBoost(),
                    targetRank.rankBoost(), relationWeightBoost);
            relationRanks.put(relation.getId(), relationRank);

            Map<String, Object> metadata = readMetadata(relation.getMetadataJson());
            metadata.put("rankAlgorithm", RANK_ALGORITHM);
            metadata.put("rankBoost", rounded(relationRank.rankBoost()));
            metadata.put("sourceEntityRankBoost", rounded(relationRank.sourceEntityRankBoost()));
            metadata.put("targetEntityRankBoost", rounded(relationRank.targetEntityRankBoost()));
            metadata.put("relationWeightBoost", rounded(relationRank.relationWeightBoost()));
            relation.setMetadataJson(writeJson(metadata));
        }
        return new GraphRankSnapshot(entityRanks, relationRanks);
    }

    private Map<Long, NodeRank> calculateEntityRanks(Map<Long, SuperAgentKgEntity> entitiesById,
            Map<Long, SuperAgentKgRelation> relationsById) {
        if (entitiesById.isEmpty()) {
            return Map.of();
        }

        Map<Long, Map<Long, Double>> outgoingWeights = new LinkedHashMap<>();
        Map<Long, Integer> inDegreeMap = new LinkedHashMap<>();
        Map<Long, Integer> outDegreeMap = new LinkedHashMap<>();
        Map<Long, Integer> degreeMap = new LinkedHashMap<>();
        Map<Long, Double> weightedDegreeMap = new LinkedHashMap<>();
        for (Long entityId : entitiesById.keySet()) {
            outgoingWeights.put(entityId, new LinkedHashMap<>());
            inDegreeMap.put(entityId, 0);
            outDegreeMap.put(entityId, 0);
            degreeMap.put(entityId, 0);
            weightedDegreeMap.put(entityId, 0D);
        }

        for (SuperAgentKgRelation relation : relationsById.values()) {
            Long sourceEntityId = relation.getSourceEntityId();
            Long targetEntityId = relation.getTargetEntityId();
            if (!entitiesById.containsKey(sourceEntityId) || !entitiesById.containsKey(targetEntityId)
                    || Objects.equals(sourceEntityId, targetEntityId)) {
                continue;
            }
            double weight = relationWeightValue(relation);
            addRankEdge(outgoingWeights, sourceEntityId, targetEntityId, weight);
            if ("ASSOCIATED_WITH".equalsIgnoreCase(relation.getRelationType())) {
                addRankEdge(outgoingWeights, targetEntityId, sourceEntityId, weight);
            }
            outDegreeMap.merge(sourceEntityId, 1, Integer::sum);
            inDegreeMap.merge(targetEntityId, 1, Integer::sum);
            degreeMap.merge(sourceEntityId, 1, Integer::sum);
            degreeMap.merge(targetEntityId, 1, Integer::sum);
            weightedDegreeMap.merge(sourceEntityId, weight, Double::sum);
            weightedDegreeMap.merge(targetEntityId, weight, Double::sum);
        }

        Map<Long, Double> pagerankMap = calculatePagerank(entitiesById.keySet(), outgoingWeights,
                relationsById.isEmpty());
        double maxPagerank = pagerankMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0D);
        List<Long> rankedEntityIds = pagerankMap.entrySet().stream().sorted(Map.Entry
                .<Long, Double>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
        Map<Long, Integer> rankPositionMap = new LinkedHashMap<>();
        for (int index = 0; index < rankedEntityIds.size(); index++) {
            rankPositionMap.put(rankedEntityIds.get(index), index + 1);
        }

        Map<Long, NodeRank> result = new LinkedHashMap<>();
        for (Long entityId : entitiesById.keySet()) {
            double pagerank = pagerankMap.getOrDefault(entityId, 0D);
            double rankBoost = relationsById.isEmpty() || maxPagerank <= 0D ? 0D : Math.sqrt(pagerank / maxPagerank);
            result.put(entityId,
                    new NodeRank(pagerank, bounded(rankBoost), degreeMap.getOrDefault(entityId, 0),
                            inDegreeMap.getOrDefault(entityId, 0), outDegreeMap.getOrDefault(entityId, 0),
                            weightedDegreeMap.getOrDefault(entityId, 0D), rankPositionMap.getOrDefault(entityId, 0)));
        }
        return result;
    }

    private Map<Long, Double> calculatePagerank(Collection<Long> entityIds,
            Map<Long, Map<Long, Double>> outgoingWeights, boolean noRelations) {
        int nodeCount = entityIds.size();
        if (nodeCount == 0) {
            return Map.of();
        }
        double initialScore = 1D / nodeCount;
        Map<Long, Double> ranks = new LinkedHashMap<>();
        for (Long entityId : entityIds) {
            ranks.put(entityId, initialScore);
        }
        if (noRelations) {
            return ranks;
        }

        for (int iteration = 0; iteration < RANK_ITERATIONS; iteration++) {
            Map<Long, Double> nextRanks = new LinkedHashMap<>();
            double baseScore = (1D - RANK_DAMPING) / nodeCount;
            for (Long entityId : entityIds) {
                nextRanks.put(entityId, baseScore);
            }

            double sinkScore = 0D;
            for (Long sourceEntityId : entityIds) {
                Map<Long, Double> outgoing = outgoingWeights.getOrDefault(sourceEntityId, Map.of());
                double totalWeight = outgoing.values().stream().mapToDouble(Double::doubleValue).sum();
                if (totalWeight <= 0D) {
                    sinkScore += ranks.getOrDefault(sourceEntityId, 0D);
                    continue;
                }
                double sourceContribution = RANK_DAMPING * ranks.getOrDefault(sourceEntityId, 0D);
                for (Map.Entry<Long, Double> edge : outgoing.entrySet()) {
                    nextRanks.merge(edge.getKey(), sourceContribution * edge.getValue() / totalWeight, Double::sum);
                }
            }

            if (sinkScore > 0D) {
                double sinkContribution = RANK_DAMPING * sinkScore / nodeCount;
                for (Long entityId : entityIds) {
                    nextRanks.merge(entityId, sinkContribution, Double::sum);
                }
            }
            ranks = nextRanks;
        }
        return ranks;
    }

    private void addRankEdge(Map<Long, Map<Long, Double>> outgoingWeights, Long sourceEntityId, Long targetEntityId,
            double weight) {
        outgoingWeights.computeIfAbsent(sourceEntityId, ignored -> new LinkedHashMap<>()).merge(targetEntityId,
                Math.max(0.05D, weight), Double::sum);
    }

    private SavedEvidences prepareEvidences(Long documentId, Long taskId,
            List<GraphRagExtractionResponse.Evidence> evidences, Map<String, Long> entityIdMap,
            Map<String, Long> relationIdMap) {
        Map<String, Long> evidenceIdMap = new LinkedHashMap<>();
        Map<Long, SuperAgentKgEvidence> evidencesById = new LinkedHashMap<>();
        Map<EvidenceIdentity, Long> evidenceIdsByIdentity = new LinkedHashMap<>();
        if (CollUtil.isEmpty(evidences)) {
            return new SavedEvidences(evidenceIdMap, evidencesById);
        }
        for (GraphRagExtractionResponse.Evidence extracted : evidences) {
            if (extracted == null || StrUtil.isBlank(extracted.getId())) {
                continue;
            }
            Long entityId = StrUtil.isBlank(extracted.getEntityId()) ? null : entityIdMap.get(extracted.getEntityId());
            Long relationId = StrUtil.isBlank(extracted.getRelationId()) ? null
                    : relationIdMap.get(extracted.getRelationId());
            if (entityId == null && relationId == null) {
                continue;
            }

            EvidenceIdentity identity = new EvidenceIdentity(entityId, relationId, extracted.getChunkId(),
                    StrUtil.blankToDefault(extracted.getQuoteText(), ""));
            Long existingEvidenceId = evidenceIdsByIdentity.get(identity);
            if (existingEvidenceId != null
                    && isCrossAuthorityDuplicate(evidencesById.get(existingEvidenceId), extracted)) {
                evidenceIdMap.put(extracted.getId(), existingEvidenceId);
                mergeEvidenceProvenance(evidencesById.get(existingEvidenceId), extracted);
                continue;
            }

            Long evidenceId = uidGenerator.getUid();
            SuperAgentKgEvidence evidence = new SuperAgentKgEvidence();
            evidence.setId(evidenceId);
            evidence.setDocumentId(documentId);
            evidence.setTaskId(taskId);
            evidence.setEntityId(entityId);
            evidence.setRelationId(relationId);
            evidence.setChunkId(extracted.getChunkId());
            evidence.setParentBlockId(extracted.getParentBlockId());
            evidence.setQuoteText(extracted.getQuoteText());
            evidence.setPageNo(extracted.getPageNo());
            evidence.setPageRange(limit(extracted.getPageRange(), 64));
            evidence.setBboxJson(extracted.getBboxJson());
            evidence.setSectionPath(limit(extracted.getSectionPath(), 1000));
            evidence.setMetadataJson(writeJson(metadata("sourceEvidenceId", extracted.getId(), "sourceEvidenceIds",
                    List.of(extracted.getId()), "sourceEntityId",
                    extracted.getEntityId(), "sourceRelationId", extracted.getRelationId(), "sourceMetadata",
                    extracted.getMetadata(), "extractorSources",
                    toStringList(
                            extracted.getMetadata() == null ? null : extracted.getMetadata().get("extractorSources")),
                    "sourceType", extracted.getMetadata() == null ? null : extracted.getMetadata().get("sourceType"))));
            evidence.setStatus(BusinessStatus.YES.getCode());
            evidencesById.put(evidenceId, evidence);
            evidenceIdMap.put(extracted.getId(), evidenceId);
            evidenceIdsByIdentity.putIfAbsent(identity, evidenceId);
        }
        return new SavedEvidences(evidenceIdMap, evidencesById);
    }

    private boolean isCrossAuthorityDuplicate(SuperAgentKgEvidence existing,
            GraphRagExtractionResponse.Evidence candidate) {
        if (existing == null || candidate == null || candidate.getMetadata() == null) {
            return false;
        }
        String existingAuthority = evidenceFactAuthority(readMetadata(existing.getMetadataJson()));
        String candidateAuthority = evidenceFactAuthority(candidate.getMetadata());
        return !Objects.equals(existingAuthority, candidateAuthority)
                && Set.of(existingAuthority, candidateAuthority).equals(Set.of(
                        GraphRagRelationAuthority.FACT_AUTHORITY_GROUNDED,
                        GraphRagRelationAuthority.FACT_AUTHORITY_STRUCTURED_ROW));
    }

    private String evidenceFactAuthority(Map<String, Object> metadata) {
        if (metadata == null) {
            return "";
        }
        Object authority = metadata.get("relationFactAuthority");
        if (authority instanceof CharSequence value) {
            return value.toString();
        }
        Object sourceMetadata = metadata.get("sourceMetadata");
        if (sourceMetadata instanceof Map<?, ?> sourceMap
                && sourceMap.get("relationFactAuthority") instanceof CharSequence value) {
            return value.toString();
        }
        return "";
    }

    private void mergeEvidenceProvenance(SuperAgentKgEvidence evidence,
            GraphRagExtractionResponse.Evidence sourceEvidence) {
        if (evidence == null || sourceEvidence == null || StrUtil.isBlank(sourceEvidence.getId())) {
            return;
        }
        Map<String, Object> metadata = readMetadata(evidence.getMetadataJson());
        LinkedHashSet<String> sourceEvidenceIds = new LinkedHashSet<>(toStringList(metadata.get("sourceEvidenceIds")));
        sourceEvidenceIds.addAll(toStringList(metadata.get("sourceEvidenceId")));
        sourceEvidenceIds.add(sourceEvidence.getId());
        sourceEvidenceIds.removeIf(StrUtil::isBlank);
        metadata.put("sourceEvidenceIds", sourceEvidenceIds);
        LinkedHashSet<String> factAuthorities = new LinkedHashSet<>(toStringList(metadata.get("relationFactAuthorities")));
        factAuthorities.addAll(toStringList(metadata.get("relationFactAuthority")));
        factAuthorities.addAll(toStringList(sourceEvidence.getMetadata() == null ? null
                : sourceEvidence.getMetadata().get("relationFactAuthority")));
        metadata.put("relationFactAuthority",
                factAuthorities.contains(GraphRagRelationAuthority.FACT_AUTHORITY_GROUNDED)
                        ? GraphRagRelationAuthority.FACT_AUTHORITY_GROUNDED
                        : factAuthorities.stream().findFirst().orElse(null));
        metadata.put("relationFactAuthorities", factAuthorities);
        evidence.setMetadataJson(writeJson(metadata));
    }

    private void insertEvidences(Collection<SuperAgentKgEvidence> evidences) {
        if (CollUtil.isEmpty(evidences)) {
            return;
        }
        for (SuperAgentKgEvidence evidence : evidences) {
            evidenceMapper.insert(evidence);
        }
    }

    private Map<String, EntityResolutionDecision> resolveEntityCanonicalKeyAdvice(
            List<GraphRagExtractionResponse.Entity> entities) {
        if (entityResolutionAdvisor == null || size(entities) < 2) {
            return Map.of();
        }

        GraphRagEntityResolutionContext context = buildEntityResolutionContext(entities);
        if (size(context.getEntities()) < 2) {
            return Map.of();
        }

        Optional<GraphRagEntityResolutionAdvice> advice;
        try {
            advice = entityResolutionAdvisor.advise(context);
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG 实体消歧 advisor 失败，继续使用 Java alias canonical 合并: message={}", exception.getMessage());
            return Map.of();
        }
        if (advice.isEmpty() || !Boolean.TRUE.equals(advice.get().getResolvable())) {
            return Map.of();
        }

        double topConfidence = bounded(toDouble(advice.get().getConfidence(), 0D));
        if (topConfidence < ENTITY_RESOLUTION_CONFIDENCE_THRESHOLD || CollUtil.isEmpty(advice.get().getMergeGroups())) {
            return Map.of();
        }
        return validateEntityResolutionAdvice(advice.get(), entities, topConfidence);
    }

    private GraphRagEntityResolutionContext buildEntityResolutionContext(
            List<GraphRagExtractionResponse.Entity> entities) {
        return GraphRagEntityResolutionContext.builder().entities(entities.stream()
                .filter(entity -> entity != null
                        && StrUtil.isNotBlank(entity.getId()) && StrUtil.isNotBlank(entity.getName()))
                .limit(ENTITY_RESOLUTION_CONTEXT_LIMIT)
                .map(entity -> GraphRagEntityResolutionContext.EntityItem.builder().sourceEntityId(entity.getId())
                        .name(entity.getName()).normalizedName(entity.getNormalizedName())
                        .entityType(limit(StrUtil.blankToDefault(entity.getType(), "CONCEPT"), 64))
                        .aliases(entity.getAliases() == null ? List.of() : entity.getAliases())
                        .description(entity.getDescription()).confidence(entity.getConfidence())
                        .sourceChunkIds(entity.getSourceChunkIds() == null ? List.of() : entity.getSourceChunkIds())
                        .evidenceIds(entity.getEvidenceIds() == null ? List.of() : entity.getEvidenceIds()).build())
                .toList()).build();
    }

    private Map<String, EntityResolutionDecision> validateEntityResolutionAdvice(GraphRagEntityResolutionAdvice advice,
            List<GraphRagExtractionResponse.Entity> entities, double topConfidence) {
        Map<String, GraphRagExtractionResponse.Entity> entityById = new LinkedHashMap<>();
        for (GraphRagExtractionResponse.Entity entity : entities) {
            if (entity != null && StrUtil.isNotBlank(entity.getId()) && StrUtil.isNotBlank(entity.getName())) {
                entityById.put(entity.getId(), entity);
            }
        }
        Map<String, EntityResolutionDecision> result = new LinkedHashMap<>();
        Set<String> claimedEntityIds = new LinkedHashSet<>();
        int groupNo = 1;
        for (GraphRagEntityResolutionAdvice.MergeGroup group : advice.getMergeGroups()) {
            EntityResolutionGroupValidation validation = validateEntityResolutionGroup(group, entityById,
                    claimedEntityIds, Math.min(topConfidence,
                            bounded(toDouble(group == null ? null : group.getConfidence(), topConfidence))),
                    groupNo);
            if (validation.accepted()) {
                result.putAll(validation.decisions());
                claimedEntityIds.addAll(validation.decisions().keySet());
                groupNo++;
            }
        }
        return result;
    }

    private EntityResolutionGroupValidation validateEntityResolutionGroup(
            GraphRagEntityResolutionAdvice.MergeGroup group, Map<String, GraphRagExtractionResponse.Entity> entityById,
            Set<String> claimedEntityIds, double confidence, int groupNo) {
        if (group == null || size(group.getEntityIds()) < 2 || confidence < ENTITY_RESOLUTION_CONFIDENCE_THRESHOLD) {
            return EntityResolutionGroupValidation.rejected();
        }
        LinkedHashSet<String> sourceEntityIds = new LinkedHashSet<>();
        for (String sourceEntityId : group.getEntityIds()) {
            if (StrUtil.isBlank(sourceEntityId) || !entityById.containsKey(sourceEntityId)
                    || claimedEntityIds.contains(sourceEntityId)) {
                return EntityResolutionGroupValidation.rejected();
            }
            sourceEntityIds.add(sourceEntityId);
        }
        if (sourceEntityIds.size() < 2) {
            return EntityResolutionGroupValidation.rejected();
        }

        String entityType = null;
        Set<String> allowedNameVariants = new LinkedHashSet<>();
        Map<String, Set<String>> variantsByEntityId = new LinkedHashMap<>();
        for (String sourceEntityId : sourceEntityIds) {
            GraphRagExtractionResponse.Entity entity = entityById.get(sourceEntityId);
            String currentType = limit(StrUtil.blankToDefault(entity.getType(), "CONCEPT"), 64);
            if (entityType == null) {
                entityType = currentType;
            }
            else if (!entityType.equals(currentType)) {
                return EntityResolutionGroupValidation.rejected();
            }
            Set<String> variants = entityResolutionVariants(entity);
            if (variants.isEmpty()) {
                return EntityResolutionGroupValidation.rejected();
            }
            allowedNameVariants.addAll(variants);
            variantsByEntityId.put(sourceEntityId, variants);
        }
        if (!canonicalNameAllowed(group.getCanonicalName(), group.getAliases(), allowedNameVariants)) {
            return EntityResolutionGroupValidation.rejected();
        }
        if (!entityResolutionGroupHasAliasOverlap(variantsByEntityId)) {
            return EntityResolutionGroupValidation.rejected();
        }

        String canonicalName = chooseResolutionCanonicalName(group, sourceEntityIds, entityById);
        String canonicalKey = canonicalEntityKey(entityType, canonicalName);
        Map<String, EntityResolutionDecision> decisions = new LinkedHashMap<>();
        for (String sourceEntityId : sourceEntityIds) {
            decisions.put(sourceEntityId, new EntityResolutionDecision(canonicalKey, sourceEntityIds.stream().toList(),
                    canonicalName, rounded(confidence), limit(group.getReason(), 500), groupNo));
        }
        return EntityResolutionGroupValidation.accepted(decisions);
    }

    private Set<String> entityResolutionVariants(GraphRagExtractionResponse.Entity entity) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        for (String variant : entityVariants(entity)) {
            if (isEntityResolutionVariantUsable(variant)) {
                variants.add(variant);
            }
        }
        return variants;
    }

    private boolean isEntityResolutionVariantUsable(String normalizedVariant) {
        if (StrUtil.isBlank(normalizedVariant) || normalizedVariant.length() > 80) {
            return false;
        }
        boolean hasLatinOrDigit = normalizedVariant.chars()
                .anyMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'));
        if (hasLatinOrDigit) {
            return normalizedVariant.length() >= 2;
        }
        return normalizedVariant.codePointCount(0, normalizedVariant.length()) >= 2;
    }

    private boolean entityResolutionGroupHasAliasOverlap(Map<String, Set<String>> variantsByEntityId) {
        if (variantsByEntityId == null || variantsByEntityId.size() < 2) {
            return false;
        }
        List<String> entityIds = new ArrayList<>(variantsByEntityId.keySet());
        Set<String> connectedEntityIds = new LinkedHashSet<>();
        connectedEntityIds.add(entityIds.get(0));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String candidateEntityId : entityIds) {
                if (connectedEntityIds.contains(candidateEntityId)) {
                    continue;
                }
                if (hasOverlapWithConnectedEntities(candidateEntityId, connectedEntityIds, variantsByEntityId)) {
                    connectedEntityIds.add(candidateEntityId);
                    changed = true;
                }
            }
        }
        return connectedEntityIds.size() == variantsByEntityId.size();
    }

    private boolean hasOverlapWithConnectedEntities(String candidateEntityId, Set<String> connectedEntityIds,
            Map<String, Set<String>> variantsByEntityId) {
        Set<String> candidateVariants = variantsByEntityId.get(candidateEntityId);
        if (CollUtil.isEmpty(candidateVariants)) {
            return false;
        }
        for (String connectedEntityId : connectedEntityIds) {
            if (variantsIntersect(candidateVariants, variantsByEntityId.get(connectedEntityId))) {
                return true;
            }
        }
        return false;
    }

    private boolean variantsIntersect(Set<String> left, Set<String> right) {
        if (CollUtil.isEmpty(left) || CollUtil.isEmpty(right)) {
            return false;
        }
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = left.size() <= right.size() ? right : left;
        for (String variant : smaller) {
            if (larger.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    private boolean canonicalNameAllowed(String canonicalName, List<String> aliases, Set<String> allowedNameVariants) {
        if (CollUtil.isEmpty(allowedNameVariants)) {
            return false;
        }
        if (StrUtil.isNotBlank(canonicalName) && !allowedNameVariants.contains(normalizeKey(canonicalName))) {
            return false;
        }
        if (CollUtil.isNotEmpty(aliases)) {
            for (String alias : aliases) {
                if (StrUtil.isNotBlank(alias) && !allowedNameVariants.contains(normalizeKey(alias))) {
                    return false;
                }
            }
        }
        return true;
    }

    private String chooseResolutionCanonicalName(GraphRagEntityResolutionAdvice.MergeGroup group,
            Set<String> sourceEntityIds, Map<String, GraphRagExtractionResponse.Entity> entityById) {
        if (StrUtil.isNotBlank(group.getCanonicalName())) {
            return limit(group.getCanonicalName(), 500);
        }
        String bestName = "";
        for (String sourceEntityId : sourceEntityIds) {
            GraphRagExtractionResponse.Entity entity = entityById.get(sourceEntityId);
            if (entity != null && shouldReplaceName(entity.getName(), bestName)) {
                bestName = entity.getName();
            }
        }
        return limit(StrUtil.blankToDefault(bestName, entityById.get(sourceEntityIds.iterator().next()).getName()),
                500);
    }

    private String canonicalEntityKey(GraphRagExtractionResponse.Entity extracted) {
        return canonicalEntityKey(limit(StrUtil.blankToDefault(extracted.getType(), "CONCEPT"), 64),
                firstNonBlank(extracted.getNormalizedName(), extracted.getName()));
    }

    private String resolveEntityCanonicalKey(GraphRagExtractionResponse.Entity extracted,
            Map<String, String> aliasIndex, Set<String> existingCanonicalKeys, String sourceKey) {
        String entityType = limit(StrUtil.blankToDefault(extracted.getType(), "CONCEPT"), 64);
        for (String variant : entityVariants(extracted)) {
            String aliasKey = entityAliasKey(entityType, variant);
            String matchedKey = aliasIndex.get(aliasKey);
            if (StrUtil.isNotBlank(matchedKey)) {
                return matchedKey;
            }
        }
        return sourceKey;
    }

    private void indexEntityAliases(Map<String, String> aliasIndex, String canonicalKey,
            EntityAccumulator accumulator) {
        for (String variant : accumulator.variants) {
            aliasIndex.put(entityAliasKey(accumulator.entityType, variant), canonicalKey);
        }
    }

    private String canonicalRelationKey(Long sourceEntityId, Long targetEntityId, String relationType) {
        if (GraphRagRelationAuthority.STORAGE_TYPE_GROUNDED_ACTION.equalsIgnoreCase(relationType)
                || GraphRagRelationAuthority.STORAGE_TYPE_ASSOCIATED_WITH.equalsIgnoreCase(relationType)) {
            return "GROUNDED_FACT:" + sourceEntityId + ":" + targetEntityId;
        }
        return relationType + ":" + sourceEntityId + ":" + targetEntityId;
    }

    private List<String> entityVariants(GraphRagExtractionResponse.Entity extracted) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        addVariant(variants, extracted.getName());
        addVariant(variants, extracted.getNormalizedName());
        if (CollUtil.isNotEmpty(extracted.getAliases())) {
            for (String alias : extracted.getAliases()) {
                addVariant(variants, alias);
            }
        }
        return new ArrayList<>(variants);
    }

    private void addVariant(Set<String> variants, String candidate) {
        String normalized = normalizeKey(candidate);
        if (StrUtil.isNotBlank(normalized)) {
            variants.add(normalized);
        }
    }

    private String entityAliasKey(String entityType, String normalizedVariant) {
        return limit(StrUtil.blankToDefault(entityType, "CONCEPT"), 64) + ":" + normalizedVariant;
    }

    private String canonicalEntityKey(String entityType, String primaryName) {
        String normalized = normalizeKey(primaryName);
        if (StrUtil.isBlank(normalized)) {
            normalized = "unknown";
        }
        return "ENT_" + sha1Hex(limit(StrUtil.blankToDefault(entityType, "CONCEPT"), 64) + ":" + normalized);
    }

    private String normalizeKey(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return value.replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}<>《》/\\\\|]+", "").toLowerCase();
    }

    private String firstNonBlank(String first, String second) {
        if (StrUtil.isNotBlank(first)) {
            return first;
        }
        return second;
    }

    private String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString().substring(0, 16).toUpperCase();
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 算法不可用", exception);
        }
    }

    private String firstNonBlank(Collection<String> values) {
        if (CollUtil.isEmpty(values)) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean shouldReplaceName(String candidate, String current) {
        if (StrUtil.isBlank(current)) {
            return StrUtil.isNotBlank(candidate);
        }
        if (StrUtil.isBlank(candidate)) {
            return false;
        }
        boolean candidateAcronym = isAcronym(candidate);
        boolean currentAcronym = isAcronym(current);
        if (currentAcronym && !candidateAcronym) {
            return true;
        }
        if (!currentAcronym && candidateAcronym) {
            return false;
        }
        return candidate.length() > current.length() + 1 && candidate.length() <= 32;
    }

    private boolean isAcronym(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        return value.matches("[A-Z][A-Z0-9._-]{1,12}");
    }

    private double toDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        }
        catch (Exception exception) {
            return defaultValue;
        }
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        }
        catch (Exception exception) {
            return defaultValue;
        }
    }

    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return new LinkedHashMap<>(metadata);
    }

    private List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            List<String> result = new ArrayList<>();
            for (Object item : collection) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (value instanceof String text) {
            if (StrUtil.isBlank(text)) {
                return List.of();
            }
            String[] parts = text.split("[\\n\\r,，;；、|]+");
            List<String> result = new ArrayList<>();
            for (String part : parts) {
                if (StrUtil.isNotBlank(part)) {
                    result.add(part.trim());
                }
            }
            return result;
        }
        return List.of(String.valueOf(value));
    }

    private Set<String> limitAliases(Collection<String> aliases, String primaryName) {
        if (CollUtil.isEmpty(aliases)) {
            return Set.of();
        }
        String normalizedPrimary = normalizeKey(primaryName);
        Set<String> result = new LinkedHashSet<>();
        for (String alias : aliases) {
            if (StrUtil.isBlank(alias)) {
                continue;
            }
            String limitedAlias = limit(alias, 500);
            if (!normalizeKey(limitedAlias).equals(normalizedPrimary)) {
                result.add(limitedAlias);
            }
        }
        return result;
    }

    private String bestText(String current, String candidate) {
        if (StrUtil.isBlank(current)) {
            return candidate;
        }
        if (StrUtil.isBlank(candidate)) {
            return current;
        }
        return candidate.length() > current.length() ? candidate : current;
    }

    private BigDecimal weight(Double value) {
        double weight = value == null ? 1.0D : value;
        return BigDecimal.valueOf(weight).setScale(4, RoundingMode.HALF_UP);
    }

    private double relationWeightValue(SuperAgentKgRelation relation) {
        if (relation == null || relation.getWeight() == null) {
            return 1D;
        }
        return Math.max(0.05D, relation.getWeight().doubleValue());
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (StrUtil.isBlank(metadataJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        }
        catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private double bounded(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private double rounded(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
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

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("GraphRAG 元数据 JSON 序列化失败", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private final class EntityAccumulator {

        private final String canonicalKey;
        private final Set<String> sourceEntityIds = new LinkedHashSet<>();
        private final Set<Long> sourceChunkIds = new LinkedHashSet<>();
        private final Set<String> evidenceIds = new LinkedHashSet<>();
        private final Set<String> aliases = new LinkedHashSet<>();
        private final Set<String> candidateSources = new LinkedHashSet<>();
        private final Set<String> extractorSources = new LinkedHashSet<>();
        private final Set<String> entityResolutionSourceEntityIds = new LinkedHashSet<>();
        private final List<Map<String, Object>> sourceMetadata = new ArrayList<>();
        private final Set<String> variants = new LinkedHashSet<>();
        private String name = "";
        private String normalizedName = "";
        private String entityType = "";
        private String description = "";
        private int mentionCount;
        private double confidence;
        private double candidateScore;
        private boolean entityResolutionEnhanced;
        private double entityResolutionConfidence;
        private String entityResolutionReason = "";
        private String entityResolutionCanonicalName = "";

        private EntityAccumulator(String canonicalKey) {
            this.canonicalKey = canonicalKey;
        }

        private void merge(GraphRagExtractionResponse.Entity extracted) {
            sourceEntityIds.add(extracted.getId());
            entityType = limit(
                    StrUtil.blankToDefault(entityType, StrUtil.blankToDefault(extracted.getType(), "CONCEPT")), 64);

            String extractedName = limit(extracted.getName(), 500);
            if (shouldReplaceName(extractedName, name)) {
                if (StrUtil.isNotBlank(name) && !normalizeKey(name).equals(normalizeKey(extractedName))) {
                    aliases.add(name);
                }
                name = extractedName;
            }
            else if (StrUtil.isNotBlank(extractedName) && !normalizeKey(extractedName).equals(normalizeKey(name))) {
                aliases.add(extractedName);
            }

            String extractedNormalizedName = limit(firstNonBlank(extracted.getNormalizedName(), extractedName), 500);
            if (StrUtil.isBlank(normalizedName) || shouldReplaceName(extractedNormalizedName, normalizedName)) {
                normalizedName = extractedNormalizedName;
            }

            description = bestText(description, limit(extracted.getDescription(), 1000));
            confidence = Math.max(confidence, extracted.getConfidence() == null ? 0D : extracted.getConfidence());
            if (CollUtil.isNotEmpty(extracted.getSourceChunkIds())) {
                sourceChunkIds.addAll(extracted.getSourceChunkIds());
            }
            if (CollUtil.isNotEmpty(extracted.getEvidenceIds())) {
                evidenceIds.addAll(extracted.getEvidenceIds());
            }
            aliases.addAll(limitAliases(extracted.getAliases(), name));
            variants.addAll(entityVariants(extracted));

            Map<String, Object> metadata = copyMetadata(extracted.getMetadata());
            if (metadata != null) {
                sourceMetadata.add(metadata);
                mentionCount += toInt(metadata.get("mentionCount"), 1);
                candidateScore += toDouble(metadata.get("candidateScore"), 0D);
                candidateSources.addAll(toStringList(metadata.get("candidateSources")));
                extractorSources.addAll(toStringList(metadata.get("extractorSources")));
                extractorSources.addAll(toStringList(metadata.get("sourceType")));
            }
            if (mentionCount <= 0) {
                mentionCount = 1;
            }
        }

        private void applyResolutionAdvice(EntityResolutionDecision decision) {
            if (decision == null) {
                return;
            }
            entityResolutionEnhanced = true;
            entityResolutionConfidence = Math.max(entityResolutionConfidence, decision.confidence());
            entityResolutionReason = bestText(entityResolutionReason, decision.reason());
            entityResolutionCanonicalName = bestText(entityResolutionCanonicalName, decision.canonicalName());
            entityResolutionSourceEntityIds.addAll(decision.sourceEntityIds());
        }
    }

    private final class RelationAccumulator {

        private final String canonicalKey;
        private final Long sourceEntityId;
        private final Long targetEntityId;
        private String relationType;
        private final Set<String> sourceRelationIds = new LinkedHashSet<>();
        private final Set<String> sourceEntityIds = new LinkedHashSet<>();
        private final Set<String> targetEntityIds = new LinkedHashSet<>();
        private final Set<String> evidenceIds = new LinkedHashSet<>();
        private final Set<String> candidateSources = new LinkedHashSet<>();
        private final Set<String> extractorSources = new LinkedHashSet<>();
        private final List<Map<String, Object>> sourceMetadata = new ArrayList<>();
        private final Set<String> groundedPredicateTexts = new LinkedHashSet<>();
        private final Set<String> relationFactAuthorities = new LinkedHashSet<>();
        private String description = "";
        private double weight;
        private double confidence;

        private RelationAccumulator(String canonicalKey, Long sourceEntityId, Long targetEntityId,
                String relationType) {
            this.canonicalKey = canonicalKey;
            this.sourceEntityId = sourceEntityId;
            this.targetEntityId = targetEntityId;
            this.relationType = relationType;
        }

        private void merge(GraphRagExtractionResponse.Relation extracted) {
            sourceRelationIds.add(extracted.getId());
            sourceEntityIds.add(extracted.getSourceEntityId());
            targetEntityIds.add(extracted.getTargetEntityId());
            if (GraphRagRelationAuthority.STORAGE_TYPE_GROUNDED_ACTION
                    .equalsIgnoreCase(extracted.getRelationType())) {
                relationType = GraphRagRelationAuthority.STORAGE_TYPE_GROUNDED_ACTION;
            }
            if (CollUtil.isNotEmpty(extracted.getEvidenceIds())) {
                evidenceIds.addAll(extracted.getEvidenceIds());
            }
            description = bestText(description, limit(extracted.getDescription(), 1000));
            weight = Math.max(weight, extracted.getWeight() == null ? 1D : extracted.getWeight());
            confidence = Math.max(confidence, extracted.getConfidence() == null ? 0D : extracted.getConfidence());
            Map<String, Object> metadata = copyMetadata(extracted.getMetadata());
            if (metadata != null) {
                sourceMetadata.add(metadata);
                groundedPredicateTexts.addAll(GraphRagRelationAuthority.groundedPredicates(metadata));
                Object factAuthority = metadata.get("relationFactAuthority");
                if (factAuthority instanceof CharSequence value && StrUtil.isNotBlank(value)) {
                    relationFactAuthorities.add(value.toString().trim());
                }
                candidateSources.addAll(toStringList(metadata.get("candidateSources")));
                extractorSources.addAll(toStringList(metadata.get("extractorSources")));
                extractorSources.addAll(toStringList(metadata.get("sourceType")));
            }
        }

        private String factAuthority() {
            if (relationFactAuthorities.contains(GraphRagRelationAuthority.FACT_AUTHORITY_GROUNDED)) {
                return GraphRagRelationAuthority.FACT_AUTHORITY_GROUNDED;
            }
            if (relationFactAuthorities.contains(GraphRagRelationAuthority.FACT_AUTHORITY_STRUCTURED_ROW)) {
                return GraphRagRelationAuthority.FACT_AUTHORITY_STRUCTURED_ROW;
            }
            return null;
        }
    }

    private record SavedEntities(Map<String, Long> sourceIdToEntityId, Map<Long, SuperAgentKgEntity> entitiesById) {
    }

    private record SavedRelations(Map<String, Long> sourceIdToRelationId,
            Map<Long, SuperAgentKgRelation> relationsById) {
    }

    private record SavedEvidences(Map<String, Long> sourceIdToEvidenceId,
            Map<Long, SuperAgentKgEvidence> evidencesById) {
    }

    private record EvidenceIdentity(Long entityId, Long relationId, Long chunkId, String quoteText) {
    }

    private record PreparedGraph(SavedEntities entities, SavedRelations relations, SavedEvidences evidences) {
    }

    private record GraphRankSnapshot(Map<Long, NodeRank> entityRanks, Map<Long, RelationRank> relationRanks) {
    }

    private record NodeRank(double pagerank, double rankBoost, int degree, int inDegree, int outDegree,
            double weightedDegree, int rankPosition) {

        private static NodeRank empty() {
            return new NodeRank(0D, 0D, 0, 0, 0, 0D, 0);
        }
    }

    private record RelationRank(double rankBoost, double sourceEntityRankBoost, double targetEntityRankBoost,
            double relationWeightBoost) {
    }

    private record EntityExtractionCandidate(String localEntityId, String name, String normalizedName,
            String entityType, List<String> aliases, String description, double confidence, List<Long> sourceChunkIds,
            String sourceId, String reason) {
    }

    private record RelationExtractionCandidate(String localRelationId, String sourceEntityId, String targetEntityId,
            String relationType, String requestedRelationType, String supportMode, String predicateQuoteText,
            String relationTypeReason, String relationTypeMappingStatus, String relationTypeMappingReason,
            String description, double weight, double confidence, Long tableId, Integer rowNo,
            Integer sourceColumnNo, Integer targetColumnNo, List<Long> structuredChunkIds) {
    }

    private record StructuredTableGrounding(GraphRagExtractionRequest.StructuredTable table,
            LinkedHashSet<Long> chunkIds, boolean ambiguous) {
    }

    private record StructuredRelationGrounding(List<Long> chunkIds, String rejectedReason) {
        private boolean valid() {
            return rejectedReason == null;
        }

        private static StructuredRelationGrounding accepted(List<Long> chunkIds) {
            return new StructuredRelationGrounding(chunkIds, null);
        }

        private static StructuredRelationGrounding rejected(String reason) {
            return new StructuredRelationGrounding(List.of(), reason);
        }
    }

    private record EntityCandidateValidation(EntityExtractionCandidate candidate, String rejectedReason) {

        private boolean valid() {
            return candidate != null && rejectedReason == null;
        }

        private static EntityCandidateValidation accepted(EntityExtractionCandidate candidate) {
            return new EntityCandidateValidation(candidate, null);
        }

        private static EntityCandidateValidation rejected(String reason) {
            return new EntityCandidateValidation(null, reason);
        }
    }

    private record GraphExtractionValidation(List<GraphRagExtractionResponse.Entity> entities,
            List<GraphRagExtractionResponse.Relation> relations, List<GraphRagExtractionResponse.Evidence> evidences,
            double confidence, String reason, String rejectedReason, List<String> rejectedEntityReasons,
            int rejectedEvidenceCount, List<String> rejectedEvidenceReasons, List<String> rejectedRelationReasons,
            int rejectedRelationEvidenceCount, List<String> rejectedRelationEvidenceReasons) {

        private boolean enhanced() {
            return rejectedReason == null && (CollUtil.isNotEmpty(entities) || CollUtil.isNotEmpty(relations)
                    || CollUtil.isNotEmpty(evidences));
        }

        private static GraphExtractionValidation accepted(List<GraphRagExtractionResponse.Entity> entities,
                List<GraphRagExtractionResponse.Relation> relations,
                List<GraphRagExtractionResponse.Evidence> evidences, double confidence, String reason,
                List<String> rejectedEntityReasons, int rejectedEvidenceCount, List<String> rejectedEvidenceReasons,
                List<String> rejectedRelationReasons, int rejectedRelationEvidenceCount,
                List<String> rejectedRelationEvidenceReasons) {
            return new GraphExtractionValidation(entities, relations, evidences, confidence, reason, null,
                    List.copyOf(rejectedEntityReasons), rejectedEvidenceCount, List.copyOf(rejectedEvidenceReasons),
                    List.copyOf(rejectedRelationReasons), rejectedRelationEvidenceCount,
                    List.copyOf(rejectedRelationEvidenceReasons));
        }

        private static GraphExtractionValidation filtered(String reason, double confidence,
                List<String> rejectedEntityReasons, int rejectedEvidenceCount, List<String> rejectedEvidenceReasons,
                List<String> rejectedRelationReasons, int rejectedRelationEvidenceCount,
                List<String> rejectedRelationEvidenceReasons) {
            return new GraphExtractionValidation(List.of(), List.of(), List.of(), confidence, null, reason,
                    List.copyOf(rejectedEntityReasons), rejectedEvidenceCount, List.copyOf(rejectedEvidenceReasons),
                    List.copyOf(rejectedRelationReasons), rejectedRelationEvidenceCount,
                    List.copyOf(rejectedRelationEvidenceReasons));
        }

        private static GraphExtractionValidation rejected(String reason) {
            return filtered(reason, 0D, List.of(), 0, List.of(), List.of(), 0, List.of());
        }
    }

    private record AdvisorEvidenceValidation(boolean valid, String reason) {

        private static AdvisorEvidenceValidation accepted() {
            return new AdvisorEvidenceValidation(true, null);
        }

        private static AdvisorEvidenceValidation invalid(String reason) {
            return new AdvisorEvidenceValidation(false, reason);
        }
    }

    private record RelationEvidenceValidation(boolean valid, String reason) {

        private static RelationEvidenceValidation accepted() {
            return new RelationEvidenceValidation(true, null);
        }

        private static RelationEvidenceValidation invalid(String reason) {
            return new RelationEvidenceValidation(false, reason);
        }
    }

    private record RelationCandidateValidation(RelationExtractionCandidate candidate, String rejectedReason) {

        private boolean valid() {
            return candidate != null && rejectedReason == null;
        }

        private static RelationCandidateValidation accepted(RelationExtractionCandidate candidate) {
            return new RelationCandidateValidation(candidate, null);
        }

        private static RelationCandidateValidation rejected(String reason) {
            return new RelationCandidateValidation(null, reason);
        }
    }

    private record EntityResolutionDecision(String canonicalKey, List<String> sourceEntityIds, String canonicalName,
            double confidence, String reason, int groupNo) {
    }

    private record EntityResolutionGroupValidation(boolean accepted, Map<String, EntityResolutionDecision> decisions) {

        private static EntityResolutionGroupValidation accepted(Map<String, EntityResolutionDecision> decisions) {
            return new EntityResolutionGroupValidation(true, decisions);
        }

        private static EntityResolutionGroupValidation rejected() {
            return new EntityResolutionGroupValidation(false, Map.of());
        }
    }
}
