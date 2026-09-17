package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagBuildResult;
import org.smartledge.ai.knowledge.augmentation.model.raptor.RaptorBuildResult;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagBuildCheckpointService;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagBuildService;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagCrossDocumentIndexService;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagTypedChunkService;
import org.smartledge.ai.knowledge.augmentation.service.RaptorBuildService;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagBuildFailureException;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagBuildOutcomePolicy;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagBuildStoppedException;
import org.smartledge.ai.knowledge.indexing.model.IndexingCohort;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.smartledge.ai.manage.config.ExecutionFailureDiagnosticProjector;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentBlock;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.manage.data.SuperAgentDocumentParentBlock;
import org.smartledge.ai.manage.data.SuperAgentDocumentParseArtifact;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.smartledge.ai.manage.data.SuperAgentDocumentStructureNode;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.data.SuperAgentDocumentTaskLog;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentStrategyPlanMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentStrategyStepMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.smartledge.ai.manage.mq.DocumentMessagingTopology;
import org.smartledge.ai.manage.mq.message.DocumentIndexBuildMessage;
import org.smartledge.ai.manage.mq.message.DocumentParseRouteMessage;
import org.smartledge.ai.manage.service.DocumentAsyncProcessService;
import org.smartledge.ai.manage.service.DocumentIndexBuildProgressCacheService;
import org.smartledge.ai.manage.service.DocumentNavigationIndexService;
import org.smartledge.ai.manage.service.DocumentParseArtifactService;
import org.smartledge.ai.manage.service.DocumentParseRouteProgressCacheService;
import org.smartledge.ai.manage.service.DocumentParserService;
import org.smartledge.ai.manage.service.DocumentProfileService;
import org.smartledge.ai.manage.service.DocumentStorageService;
import org.smartledge.ai.manage.service.DocumentStrategyService;
import org.smartledge.ai.manage.service.DocumentStructureGraphProjectionService;
import org.smartledge.ai.manage.service.DocumentStructureNodeService;
import org.smartledge.ai.manage.service.DocumentTaskLogService;
import org.smartledge.ai.manage.service.DocumentVectorGateway;
import org.smartledge.ai.manage.service.keyword.DocumentKeywordSearchGateway;
import org.smartledge.ai.manage.support.ChunkCandidate;
import org.smartledge.ai.manage.support.ChunkSourceProvenance;
import org.smartledge.ai.manage.support.ChunkingContract;
import org.smartledge.ai.manage.support.ChunkingProfileException;
import org.smartledge.ai.manage.support.DocumentAnalysisResult;
import org.smartledge.ai.manage.support.DocumentBlockCandidate;
import org.smartledge.ai.manage.support.DocumentParseArtifactCandidate;
import org.smartledge.ai.manage.support.DocumentStrategyPlanDraft;
import org.smartledge.ai.manage.support.DocumentStrategyStepDraft;
import org.smartledge.ai.manage.support.DocumentTableCandidate;
import org.smartledge.ai.manage.support.MybatisBatchExecutor;
import org.smartledge.ai.manage.support.ParentBlockCandidate;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentChunkSourceTypeEnum;
import org.smartledge.enums.DocumentFileTypeEnum;
import org.smartledge.enums.DocumentIndexStatusEnum;
import org.smartledge.enums.DocumentLogLevelEnum;
import org.smartledge.enums.DocumentOperatorTypeEnum;
import org.smartledge.enums.DocumentParseStatusEnum;
import org.smartledge.enums.DocumentPlanSourceEnum;
import org.smartledge.enums.DocumentPlanStatusEnum;
import org.smartledge.enums.DocumentStrategyExecuteStatusEnum;
import org.smartledge.enums.DocumentStrategyPipelineTypeEnum;
import org.smartledge.enums.DocumentStrategyStatusEnum;
import org.smartledge.enums.DocumentTaskEventTypeEnum;
import org.smartledge.enums.DocumentTaskStageEnum;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;
import org.smartledge.enums.DocumentVectorStatusEnum;
import org.smartledge.enums.DocumentVectorStoreTypeEnum;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import org.smartledge.ai.manage.support.DerivedRowTenantScope;
import org.smartledge.ai.manage.support.DocumentTenantLookup;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@Slf4j
@RequiredArgsConstructor
@Service
public class DocumentAsyncProcessServiceImpl implements DocumentAsyncProcessService {

    private static final TypeReference<Map<String, Object>> EXT_JSON_TYPE = new TypeReference<>() {
    };

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentStrategyPlanMapper planMapper;

    private final SuperAgentDocumentStrategyStepMapper stepMapper;

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final DocumentStorageService storageService;

    private final DocumentParseArtifactService parseArtifactService;

    private final DocumentParserService parserService;

    private final DocumentStrategyService strategyService;

    private final DocumentStructureNodeService structureNodeService;

    private final DocumentTaskLogService taskLogService;

    private final DocumentIndexBuildProgressCacheService progressCacheService;

    private final DocumentParseRouteProgressCacheService parseRouteProgressCacheService;

    private final DocumentVectorGateway vectorGateway;

    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;

    private final ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider;

    private final ObjectProvider<DocumentStructureGraphProjectionService> graphProjectionServiceProvider;

    private final DocumentProfileService documentProfileService;

    private final GraphRagBuildService graphRagBuildService;

    private final GraphRagBuildCheckpointService graphRagBuildCheckpointService;

    private final GraphRagCrossDocumentIndexService crossDocumentIndexService;

    private final GraphRagTypedChunkService graphRagTypedChunkService;

    private final RaptorBuildService raptorBuildService;

    private final DocumentManageProperties properties;

    /** 派生写入的租户权威（S22 批次 2）：租户只来自父文档行，应用层实体没有 tenant_id 字段。 */
    private final DocumentTenantLookup documentTenantLookup;

    private final ExecutionFailureDiagnosticProjector failureDiagnosticProjector;

    private final ObjectMapper objectMapper;

    private final ObjectProvider<LlmChunkKeywordQuestionAdvisor> chunkKeywordQuestionAdvisorProvider;

    private final DocumentMessagingTopology messagingTopology;

    private final GraphRagBuildOutcomePolicy graphRagOutcomePolicy = new GraphRagBuildOutcomePolicy();

    @Resource
    private UidGenerator uidGenerator;

    @Resource(name = "documentIndexBuildExecutorService")
    private ExecutorService indexBuildExecutorService;

    @Resource(name = "documentDatasetRaptorExecutorService")
    private ExecutorService datasetRaptorExecutorService;

    @Override
    public void handleParseRoute(Long documentId, Long taskId) {
        // 消息消费线程不在 Web 请求内，必须显式建立系统租户上下文，
        // 否则租户拦截器会因缺少上下文而拒绝构造查询（fail closed）。
        TenantContext.runAsSystem(() -> doHandleParseRoute(documentId, taskId));
    }

    private void doHandleParseRoute(Long documentId, Long taskId) {

        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (document == null || task == null) {
            log.warn("解析任务对应的文档或任务不存在，documentId={}, taskId={}", documentId, taskId);
            return;
        }

        Date startTime = new Date();
        long parseRouteStartedNanos = System.nanoTime();
        log.info("开始异步解析文档，documentId={}, taskId={}, fileName={}, fileType={}, objectName={}", documentId, taskId,
                document.getOriginalFileName(), document.getFileType(), document.getObjectName());
        try {

            // 解析阶段的按文档写入（blocks / structure_node / 文档画像）同样必须在父文档租户作用域内，
            // 否则这些派生行会取列默认租户（S22 批次 2 / S22-E）。
            final Long documentTenantId = DerivedRowTenantScope
                    .requireDocumentTenant(documentTenantLookup.tenantOfDocument(documentId));

            if (!claimTaskExecution(task, DocumentTaskStageEnum.CONTENT_PARSE.getCode(), startTime)) {
                log.info("解析任务已被其它实例抢占或已结束，跳过本次执行，documentId={}, taskId={}", documentId, taskId);
                return;
            }

            document.setParseStatus(DocumentParseStatusEnum.PARSING.getCode());
            documentMapper.updateById(document);
            parseRouteProgressCacheService.init(document, task);

            saveParseRouteLog(taskId, documentId, DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                    DocumentTaskEventTypeEnum.START.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                    DocumentOperatorTypeEnum.SYSTEM.getCode(), null, "开始解析文档内容。",
                    Map.of("objectName", document.getObjectName()));

            saveParseRouteLog(taskId, documentId, DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                    DocumentTaskEventTypeEnum.START.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                    DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                    "解析方式已确定：" + parseModeDescription(document.getFileType()) + "。",
                    detail("parseMode", parseModeCode(document.getFileType()), "fileType", document.getFileType(),
                            "fileName", document.getOriginalFileName()));

            long downloadStartedNanos = System.nanoTime();
            byte[] fileBytes = storageService.downloadObject(document.getObjectName());
            long downloadCostMillis = elapsedMillis(downloadStartedNanos);
            log.info("解析源文件下载完成，documentId={}, taskId={}, objectName={}, fileSizeBytes={}, costMillis={}", documentId,
                    taskId, document.getObjectName(), fileBytes == null ? 0 : fileBytes.length, downloadCostMillis);

            long parserStartedNanos = System.nanoTime();
            DocumentAnalysisResult analysisResult = parserService.parse(fileBytes, document.getOriginalFileName(),
                    document.getMimeType(), DocumentFileTypeEnum.getRc(document.getFileType()));
            long parserCostMillis = elapsedMillis(parserStartedNanos);
            int artifactCount = analysisResult.getParseArtifacts() == null ? 0
                    : analysisResult.getParseArtifacts().size();
            int blockCount = analysisResult.getBlocks() == null ? 0 : analysisResult.getBlocks().size();
            int structureCandidateCount = analysisResult.getStructureNodes() == null ? 0
                    : analysisResult.getStructureNodes().size();
            int tableCandidateCount = analysisResult.getTableCandidates() == null ? 0
                    : analysisResult.getTableCandidates().size();
            log.info(
                    "文档解析服务调用完成，documentId={}, taskId={}, fileName={}, charCount={}, tokenCount={}, blockCount={}, artifactCount={}, structureCandidateCount={}, tableCandidateCount={}, costMillis={}",
                    documentId, taskId, document.getOriginalFileName(), analysisResult.getCharCount(),
                    analysisResult.getTokenCount(), blockCount, artifactCount, structureCandidateCount,
                    tableCandidateCount, parserCostMillis);
            saveParseRouteLog(taskId, documentId, DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                    DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                    DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                    "解析器返回结果，block " + blockCount + " 个，artifact " + artifactCount + " 个，结构候选 "
                            + structureCandidateCount + " 个。",
                    detail("parserProviderName", analysisResult.getParserProviderName(), "parserProviderVersion",
                            analysisResult.getParserProviderVersion(), "parserTraceMetadata",
                            analysisResult.getParserTraceMetadata(), "artifactCount", artifactCount, "blockCount",
                            blockCount, "structureCandidateCount", structureCandidateCount, "tableCandidateCount",
                            tableCandidateCount, "parserCostMillis", parserCostMillis));

            long parsedTextUploadStartedNanos = System.nanoTime();
            String parseTextPath = storageService.uploadParsedText(documentId, analysisResult.getParsedText());
            long parsedTextUploadCostMillis = elapsedMillis(parsedTextUploadStartedNanos);
            log.info("解析文本上传完成，documentId={}, taskId={}, parseTextPath={}, charCount={}, costMillis={}", documentId,
                    taskId, parseTextPath, analysisResult.getCharCount(), parsedTextUploadCostMillis);

            long artifactPersistStartedNanos = System.nanoTime();
            DerivedRowTenantScope.runPerDocument(documentTenantId,
                    () -> saveParseArtifactsAndBlocks(documentId, taskId, analysisResult));
            long artifactPersistCostMillis = elapsedMillis(artifactPersistStartedNanos);
            log.info("解析产物和 blocks 入库完成，documentId={}, taskId={}, artifactCount={}, blockCount={}, costMillis={}",
                    documentId, taskId, artifactCount, blockCount, artifactPersistCostMillis);

            long structurePersistStartedNanos = System.nanoTime();
            List<SuperAgentDocumentStructureNode> structureNodes = DerivedRowTenantScope.callPerDocument(
                    documentTenantId,
                    () -> structureNodeService.replaceDocumentNodes(documentId, taskId,
                            analysisResult.getStructureNodes()));
            long structurePersistCostMillis = elapsedMillis(structurePersistStartedNanos);
            int structureNodeCount = structureNodes.size();
            log.info("结构节点入库完成，documentId={}, taskId={}, structureNodeCount={}, costMillis={}", documentId, taskId,
                    structureNodeCount, structurePersistCostMillis);

            long navigationStartedNanos = System.nanoTime();
            DerivedRowTenantScope.runPerDocument(documentTenantId,
                    () -> syncNavigationArtifacts(documentId, taskId, structureNodes));
            long navigationCostMillis = elapsedMillis(navigationStartedNanos);
            log.info("导航产物同步完成，documentId={}, taskId={}, structureNodeCount={}, costMillis={}", documentId, taskId,
                    structureNodeCount, navigationCostMillis);

            long profileStartedNanos = System.nanoTime();
            DerivedRowTenantScope.runPerDocument(documentTenantId,
                    () -> documentProfileService.generateProfile(documentId, analysisResult, structureNodes));
            long profileCostMillis = elapsedMillis(profileStartedNanos);
            log.info("文档画像生成流程完成，documentId={}, taskId={}, costMillis={}", documentId, taskId, profileCostMillis);
            saveParseRouteLog(taskId, documentId, DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                    DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                    DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                    "解析产物入库完成，已保存 parsed text、artifact、block、structure 和文档画像。",
                    detail("parseTextPath", parseTextPath, "artifactCount", artifactCount, "blockCount", blockCount,
                            "structureNodeCount", structureNodeCount, "artifactPersistCostMillis",
                            artifactPersistCostMillis, "structurePersistCostMillis", structurePersistCostMillis,
                            "navigationCostMillis", navigationCostMillis, "profileCostMillis", profileCostMillis));

            saveParseRouteLog(taskId, documentId, DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                    DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                    DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                    "文档解析完成，解析服务耗时 " + parserCostMillis + "ms，总耗时 " + elapsedMillis(parseRouteStartedNanos) + "ms。",
                    detail("charCount", analysisResult.getCharCount(), "tokenCount", analysisResult.getTokenCount(),
                            "structureLevel", analysisResult.getStructureLevel(), "contentQualityLevel",
                            analysisResult.getContentQualityLevel(), "parserProviderName",
                            analysisResult.getParserProviderName(), "parserProviderVersion",
                            analysisResult.getParserProviderVersion(), "parserCapabilities",
                            analysisResult.getParserCapabilities(), "parserElapsedMs",
                            analysisResult.getParserElapsedMs(), "parserWarnings", analysisResult.getParserWarnings(),
                            "parserTraceMetadata", analysisResult.getParserTraceMetadata(), "structureNodeCount",
                            structureNodeCount, "artifactCount", artifactCount, "blockCount", blockCount,
                            "downloadCostMillis", downloadCostMillis, "parserCostMillis", parserCostMillis,
                            "parsedTextUploadCostMillis", parsedTextUploadCostMillis, "artifactPersistCostMillis",
                            artifactPersistCostMillis, "structurePersistCostMillis", structurePersistCostMillis,
                            "navigationCostMillis", navigationCostMillis, "profileCostMillis", profileCostMillis,
                            "costMillis", elapsedMillis(parseRouteStartedNanos)));

            task.setCurrentStage(DocumentTaskStageEnum.STRATEGY_ROUTE.getCode());
            updateTaskState(task);
            parseRouteProgressCacheService.update(document, task);
            saveParseRouteLog(taskId, documentId, DocumentTaskStageEnum.STRATEGY_ROUTE.getCode(),
                    DocumentTaskEventTypeEnum.START.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                    DocumentOperatorTypeEnum.SYSTEM.getCode(), null, "开始分析解析结果并生成推荐策略。",
                    detail("blockCount", blockCount, "structureNodeCount", structureNodeCount, "charCount",
                            analysisResult.getCharCount(), "tokenCount", analysisResult.getTokenCount()));

            long strategyRecommendStartedNanos = System.nanoTime();
            document.setLastParseTaskId(taskId);
            DocumentStrategyPlanDraft planDraft = strategyService.recommendStrategy(document, analysisResult);
            long strategyRecommendCostMillis = elapsedMillis(strategyRecommendStartedNanos);
            log.info("文档策略推荐计算完成，documentId={}, taskId={}, parentStepCount={}, childStepCount={}, costMillis={}",
                    documentId, taskId, planDraft.getParentSteps().size(), planDraft.getChildSteps().size(),
                    strategyRecommendCostMillis);

            Long planId = uidGenerator.getUid();
            int planVersion = getNextPlanVersion(documentId);

            long strategyPersistStartedNanos = System.nanoTime();
            SuperAgentDocumentStrategyPlan plan = new SuperAgentDocumentStrategyPlan();
            plan.setId(planId);
            plan.setTenantId(documentTenantId);
            plan.setDocumentId(documentId);
            plan.setPlanVersion(planVersion);
            plan.setPlanSource(DocumentPlanSourceEnum.SYSTEM_RECOMMEND.getCode());
            plan.setPlanStatus(DocumentPlanStatusEnum.WAIT_CONFIRM.getCode());
            plan.setStrategyCount(planDraft.getParentSteps().size() + planDraft.getChildSteps().size());
            plan.setStrategySnapshot(planDraft.getStrategySnapshot());
            plan.setChunkingContractJson(planDraft.getChunkingContractJson());
            plan.setRecommendReason(planDraft.getRecommendReason());
            plan.setStatus(BusinessStatus.YES.getCode());
            DerivedRowTenantScope.runPerDocument(documentTenantId, () -> planMapper.insert(plan));

            for (int index = 0; index < planDraft.getParentSteps().size(); index++) {
                DocumentStrategyStepDraft draft = planDraft.getParentSteps().get(index);
                SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
                step.setId(uidGenerator.getUid());
                step.setTenantId(documentTenantId);
                step.setPlanId(planId);
                step.setDocumentId(documentId);
                step.setPipelineType(draft.getPipelineType());
                step.setStepNo(index + 1);
                step.setStrategyType(draft.getStrategyType());
                step.setStrategyRole(draft.getStrategyRole());
                step.setSourceType(draft.getSourceType());
                step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());
                step.setRecommendReason(draft.getRecommendReason());
                step.setStatus(BusinessStatus.YES.getCode());
                DerivedRowTenantScope.runPerDocument(documentTenantId, () -> stepMapper.insert(step));
            }
            for (int index = 0; index < planDraft.getChildSteps().size(); index++) {
                DocumentStrategyStepDraft draft = planDraft.getChildSteps().get(index);
                SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
                step.setId(uidGenerator.getUid());
                step.setTenantId(documentTenantId);
                step.setPlanId(planId);
                step.setDocumentId(documentId);
                step.setPipelineType(draft.getPipelineType());
                step.setStepNo(index + 1);
                step.setStrategyType(draft.getStrategyType());
                step.setStrategyRole(draft.getStrategyRole());
                step.setSourceType(draft.getSourceType());
                step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());
                step.setRecommendReason(draft.getRecommendReason());
                step.setStatus(BusinessStatus.YES.getCode());
                DerivedRowTenantScope.runPerDocument(documentTenantId, () -> stepMapper.insert(step));
            }
            long strategyPersistCostMillis = elapsedMillis(strategyPersistStartedNanos);
            log.info(
                    "文档推荐策略入库完成，documentId={}, taskId={}, planId={}, planVersion={}, parentStepCount={}, childStepCount={}, costMillis={}",
                    documentId, taskId, planId, planVersion, planDraft.getParentSteps().size(),
                    planDraft.getChildSteps().size(), strategyPersistCostMillis);

            document.setParseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode());
            document.setStrategyStatus(DocumentStrategyStatusEnum.RECOMMENDED.getCode());
            document.setCharCount(analysisResult.getCharCount());
            document.setTokenCount(analysisResult.getTokenCount());
            document.setStructureLevel(analysisResult.getStructureLevel());
            document.setContentQualityLevel(analysisResult.getContentQualityLevel());
            document.setParseTextPath(parseTextPath);
            document.setParseErrorMsg(null);
            document.setCurrentPlanId(planId);
            document.setLastParseTaskId(taskId);
            document.setStructureNodeCount(structureNodeCount);
            documentMapper.updateById(document);

            persistParserTraceMetadata(taskId, analysisResult);

            finishTaskSuccess(task, DocumentTaskStageEnum.STRATEGY_ROUTE.getCode(), startTime);
            saveParseRouteLog(taskId, documentId, DocumentTaskStageEnum.STRATEGY_ROUTE.getCode(),
                    DocumentTaskEventTypeEnum.RECOMMEND_STRATEGY.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                    DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                    "系统已生成推荐策略，推荐耗时 " + strategyRecommendCostMillis + "ms，入库耗时 " + strategyPersistCostMillis + "ms，总耗时 "
                            + elapsedMillis(parseRouteStartedNanos) + "ms。",
                    detail("planId", planId, "strategySnapshot", planDraft.getStrategySnapshot(), "parentStepCount",
                            planDraft.getParentSteps().size(), "childStepCount", planDraft.getChildSteps().size(),
                            "structureNodeCount", structureNodeCount, "recommendReason", planDraft.getRecommendReason(),
                            "strategyRecommendCostMillis", strategyRecommendCostMillis, "strategyPersistCostMillis",
                            strategyPersistCostMillis, "costMillis", elapsedMillis(parseRouteStartedNanos)),
                    plan, listSteps(planId));
            log.info(
                    "异步解析文档完成，documentId={}, taskId={}, planId={}, charCount={}, tokenCount={}, blockCount={}, structureNodeCount={}, costMillis={}",
                    documentId, taskId, planId, analysisResult.getCharCount(), analysisResult.getTokenCount(),
                    blockCount, structureNodeCount, elapsedMillis(parseRouteStartedNanos));
        }
        catch (Exception exception) {
            long failedCostMillis = elapsedMillis(parseRouteStartedNanos);
            Integer failedStage = task.getCurrentStage() == null ? DocumentTaskStageEnum.CONTENT_PARSE.getCode()
                    : task.getCurrentStage();
            log.error("异步解析文档失败，documentId={}, taskId={}, currentStage={}, costMillis={}", documentId, taskId,
                    stageLabel(failedStage), failedCostMillis, exception);

            document.setParseStatus(DocumentParseStatusEnum.PARSE_FAILED.getCode());
            document.setParseErrorMsg(exception.getMessage());
            documentMapper.updateById(document);

            failTask(task, startTime, exception, failedStage);
            saveParseRouteLog(taskId, documentId, failedStage, DocumentTaskEventTypeEnum.FAILED.getCode(),
                    DocumentLogLevelEnum.ERROR.getCode(), DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                    "文档解析失败，当前阶段 " + stageLabel(failedStage) + "，已耗时 " + failedCostMillis + "ms。",
                    detail("error", exception.getMessage(), "currentStage", failedStage, "currentStageName",
                            stageName(failedStage), "costMillis", failedCostMillis));
        }
    }

    @Override
    public void submitIndexBuild(Long documentId, Long taskId, Long planId) {
        // 本方法由消息消费者调用，消费线程不在 Web 请求内，方法体本身就要查任务状态，
        // 因此必须在入口声明系统上下文；只在提交给线程池的内部任务上声明是不够的
        // （实测：开启租户开关后消息在 taskMapper.selectById 处因缺少上下文失败并进入死信）。
        TenantContext.runAsSystem(() -> doSubmitIndexBuild(documentId, taskId, planId));
    }

    private void doSubmitIndexBuild(Long documentId, Long taskId, Long planId) {
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("索引构建任务不存在，跳过提交后台执行，documentId={}, taskId={}, planId={}", documentId, taskId, planId);
            return;
        }
        if (isTaskFinished(task)) {
            log.info("索引构建任务已结束，跳过重复投递消息，documentId={}, taskId={}, planId={}, status={}",
                documentId, taskId, planId, task.getTaskStatus());
            return;
        }

        indexBuildExecutorService.execute(() -> TenantContext.runAsSystem(() -> doHandleIndexBuild(documentId, taskId, planId)));
        log.info("索引构建任务已提交后台执行，documentId={}, taskId={}, planId={}", documentId, taskId, planId);
    }

    @Override
    public void handleIndexBuild(Long documentId, Long taskId, Long planId) {
        TenantContext.runAsSystem(() -> doHandleIndexBuild(documentId, taskId, planId));
    }

    @Override
    public void handleDeadLetter(String messageType, String payload) {
        // 死信消费线程同样不在 Web 请求内，且死信消息只带文档/任务 id，
        // 需要跨租户定位并修复任务状态，因此显式声明系统上下文（与对账任务同一口径）。
        TenantContext.runAsSystem(() -> doHandleDeadLetter(messageType, payload));
    }

    private void doHandleDeadLetter(String messageType, String payload) {

        if (StrUtil.isBlank(payload)) {
            log.error("死信消息体为空，messageType={}", messageType);
            return;
        }
        Long documentId;
        Long taskId;
        try {
            if (Objects.equals(messageType, messagingTopology.indexBuildRoutingKey())) {
                DocumentIndexBuildMessage message = objectMapper.readValue(payload, DocumentIndexBuildMessage.class);
                documentId = message.getDocumentId();
                taskId = message.getTaskId();
            }
            else if (Objects.equals(messageType, messagingTopology.parseRouteRoutingKey())) {
                DocumentParseRouteMessage message = objectMapper.readValue(payload, DocumentParseRouteMessage.class);
                documentId = message.getDocumentId();
                taskId = message.getTaskId();
            }
            else {
                log.error("无法识别的死信消息类型，messageType={}, payload={}", messageType, payload);
                return;
            }
        }
        catch (Exception exception) {
            log.error("死信消息体无法解析，messageType={}, payload={}", messageType, payload, exception);
            return;
        }
        failTaskByDeadLetter(documentId, taskId);
    }

    private void failTaskByDeadLetter(Long documentId, Long taskId) {

        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("死信对应的任务不存在，documentId={}, taskId={}", documentId, taskId);
            return;
        }
        if (isTaskFinished(task)) {
            log.info("死信对应的任务已结束，无需重复标记，documentId={}, taskId={}, status={}",
                documentId, taskId, task.getTaskStatus());
            return;
        }
        Date finishTime = new Date();
        Date startTime = task.getStartTime() == null ? task.getCreateTime() : task.getStartTime();
        taskMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getId, taskId)
            .in(SuperAgentDocumentTask::getTaskStatus,
                DocumentTaskStatusEnum.NEW.getCode(),
                DocumentTaskStatusEnum.RUNNING.getCode())
            .set(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.FAILED.getCode())
            .set(SuperAgentDocumentTask::getFinishTime, finishTime)
            .set(SuperAgentDocumentTask::getCostMillis,
                startTime == null ? 0L : Math.max(0L, finishTime.getTime() - startTime.getTime()))
            .set(SuperAgentDocumentTask::getErrorCode, "MESSAGE_DEAD_LETTERED")
            .set(SuperAgentDocumentTask::getErrorMsg, "触发消息重试耗尽并进入死信队列，任务已标记失败。"));
        taskLogService.saveLog(taskId, documentId,
            task.getCurrentStage() == null ? DocumentTaskStageEnum.FILE_UPLOAD.getCode() : task.getCurrentStage(),
            DocumentTaskEventTypeEnum.FAILED.getCode(),
            DocumentLogLevelEnum.ERROR.getCode(),
            DocumentOperatorTypeEnum.SYSTEM.getCode(),
            null,
            "触发消息重试耗尽并进入死信队列，任务已标记失败。",
            Map.of("errorCode", "MESSAGE_DEAD_LETTERED", "source", "dead-letter"));
        releaseDocumentStatus(task, documentId);
    }

    private void releaseDocumentStatus(SuperAgentDocumentTask task, Long documentId) {

        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            return;
        }
        if (Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.BUILD_INDEX.getCode())
            && Objects.equals(document.getIndexStatus(), DocumentIndexStatusEnum.BUILDING.getCode())) {
            documentMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocument>()
                .eq(SuperAgentDocument::getId, documentId)
                .set(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_FAILED.getCode()));
        }
        if (Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())
            && Objects.equals(document.getParseStatus(), DocumentParseStatusEnum.PARSING.getCode())) {
            documentMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocument>()
                .eq(SuperAgentDocument::getId, documentId)
                .set(SuperAgentDocument::getParseStatus, DocumentParseStatusEnum.PARSE_FAILED.getCode()));
        }
    }

    private boolean isTaskFinished(SuperAgentDocumentTask task) {
        return Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())
            || Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.FAILED.getCode())
            || Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.CANCELED.getCode());
    }

    /**
     * 原子抢占任务执行权。
     *
     * <p>只有把任务从待执行推进到执行中成功的那一次调用获得执行权。这是跨实例唯一的幂等保护：
     * 旧的实现用进程内静态集合判断，同一 JVM 内第二次提交会被拒绝，但多实例部署时两个实例会同时执行同一任务。</p>
     *
     * @return true 表示本次调用获得执行权
     */
    private boolean claimTaskExecution(SuperAgentDocumentTask task, Integer stage, Date startTime) {

        if (task == null || task.getId() == null) {
            return false;
        }
        Date claimedStartTime = startTime == null ? new Date() : startTime;
        SuperAgentDocumentTask update = new SuperAgentDocumentTask();
        update.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
        update.setCurrentStage(stage);
        update.setStartTime(claimedStartTime);
        int affected = taskMapper.update(update, new LambdaUpdateWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getId, task.getId())
            .eq(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.NEW.getCode())
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode()));
        if (affected != 1) {
            return false;
        }
        task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
        task.setCurrentStage(stage);
        task.setStartTime(claimedStartTime);
        return true;
    }

    private void doHandleIndexBuild(Long documentId, Long taskId, Long planId) {

        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        SuperAgentDocumentStrategyPlan plan = planMapper.selectById(planId);
        if (document == null || task == null || plan == null) {
            log.warn("索引任务对应的数据不存在，documentId={}, taskId={}, planId={}", documentId, taskId, planId);
            return;
        }
        if (Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())) {
            log.info("索引构建任务已成功，跳过重复执行，documentId={}, taskId={}, planId={}", documentId, taskId, planId);
            return;
        }
        if (Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.FAILED.getCode())) {
            log.info("索引构建任务已失败，跳过重复执行，documentId={}, taskId={}, planId={}", documentId, taskId, planId);
            return;
        }

        Date startTime = new Date();
        GraphRagBuildResult graphRagBuildResult = readGraphRagBuildResult(task);
        if (graphRagBuildResult != null && graphRagBuildResult
                .getOuterTaskDisposition() == GraphRagBuildResult.OuterTaskDisposition.FAIL_INDEX_TASK) {
            applyGraphFailureDisposition(document, task, planId, startTime, graphRagBuildResult, null);
            return;
        }
        long buildStartedNanos = System.nanoTime();
        log.info("开始执行索引构建任务，documentId={}, taskId={}, planId={}, sourceParseTaskId={}", documentId, taskId, planId,
                task.getSourceParseTaskId());

        List<SuperAgentDocumentStrategyStep> stepList = listSteps(planId);
        log.info("索引构建策略步骤读取完成，documentId={}, taskId={}, planId={}, stepCount={}", documentId, taskId, planId,
                stepList.size());
        try {

            // 按文档派生写入的租户来源（S22 批次 2）：唯一权威是父文档行（应用层实体没有 tenant_id 字段），
            // 不由调用方另行决定。构建链路整体在系统上下文下运行，派生行否则只能取列默认值 1；
            // 把写路径包进这个租户作用域，租户拦截器才会为派生表注入 tenant_id。
            // 解析失败即按前置缺失处理（任务失败），绝不退化成默认租户继续写。
            final Long documentTenantId = DerivedRowTenantScope
                    .requireDocumentTenant(documentTenantLookup.tenantOfDocument(documentId));
            Long sourceParseTaskId = requireSourceParseTaskId(document, task);
            ChunkingContract chunkingContract = ChunkingContract.read(plan.getChunkingContractJson(), objectMapper);
            if (chunkingContract != null) {
                chunkingContract.requireFrozenTask(task, objectMapper);
                chunkingContract.requireStrategies(stepList);
                IndexingCohort.freeze(document, task, taskMapper.selectById(sourceParseTaskId), plan);
            }

            if (!claimTaskExecution(task, DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(), startTime)) {
                log.info("索引构建任务已被其它实例抢占或已结束，跳过本次执行，documentId={}, taskId={}", documentId, taskId);
                return;
            }
            document.setIndexStatus(DocumentIndexStatusEnum.BUILDING.getCode());
            documentMapper.updateById(document);
            progressCacheService.init(document, task);

            graphRagBuildResult = readGraphRagBuildResult(task);
            // final：按文档写路径要在父文档租户作用域（lambda）里执行，非 final 的局部量无法捕获。
            final List<SuperAgentDocumentParentBlock> parentBlockEntityList;
            final List<SuperAgentDocumentChunk> chunkEntityList;
            boolean resumeCommittedGraph = isCommittedGraph(graphRagBuildResult);
            if (resumeCommittedGraph) {
                parentBlockEntityList = List.of();
                chunkEntityList = listFrozenSourceChunks(documentId, taskId);
                graphRagBuildResult = repairCrossDocumentProjection(document, documentId, taskId, graphRagBuildResult);
                log.info(
                        "从已提交 GraphRAG outcome 恢复索引任务: documentId={}, taskId={}, persistenceOutcome={}, crossOutcome={}, typedOutcome={}",
                        documentId, taskId, graphRagBuildResult.getGraphPersistenceOutcome(),
                        graphRagBuildResult.getCrossDocumentIndexOutcome(), graphRagBuildResult.getTypedIndexOutcome());
            }
            else {
                updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTING.getCode());

                saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                        DocumentTaskEventTypeEnum.START.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                        DocumentOperatorTypeEnum.SYSTEM.getCode(), null, "开始执行切块流水线。",
                        Map.of("strategySnapshot", plan.getStrategySnapshot()));

                long chunkStartedNanos = System.nanoTime();
                long blockLoadStartedNanos = System.nanoTime();
                List<SuperAgentDocumentBlock> documentBlocks = parseArtifactService.listBlocks(documentId,
                        sourceParseTaskId);
                long blockLoadCostMillis = elapsedMillis(blockLoadStartedNanos);
                log.info(
                        "索引构建读取解析 blocks 完成，documentId={}, taskId={}, sourceParseTaskId={}, blockCount={}, costMillis={}",
                        documentId, taskId, sourceParseTaskId, documentBlocks.size(), blockLoadCostMillis);
                if (documentBlocks.isEmpty()) {
                    throw new IllegalStateException("当前文档没有结构化解析 blocks，无法执行 Parent/Child 切块。");
                }

                long parentBuildStartedNanos = System.nanoTime();
                List<ParentBlockCandidate> parentBlockCandidateList = strategyService.buildParentBlocks(document, plan,
                        stepList, documentBlocks);
                long parentBuildCostMillis = elapsedMillis(parentBuildStartedNanos);
                long chunkCostMillis = elapsedMillis(chunkStartedNanos);
                log.info(
                        "切块流水线执行完成，documentId={}, taskId={}, blockCount={}, parentCount={}, childCount={}, blockLoadCostMillis={}, parentBuildCostMillis={}, costMillis={}",
                        documentId, taskId, documentBlocks.size(), parentBlockCandidateList.size(),
                        countChildCandidates(parentBlockCandidateList), blockLoadCostMillis, parentBuildCostMillis,
                        chunkCostMillis);

                updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTE_SUCCESS.getCode());

                saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                        DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                        DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                        "切块执行完成，读取 blocks 耗时 " + blockLoadCostMillis + "ms，Parent/Child 候选构建耗时 " + parentBuildCostMillis
                                + "ms，总耗时 " + chunkCostMillis + "ms。",
                        Map.of("parentCount", parentBlockCandidateList.size(), "childCount",
                                countChildCandidates(parentBlockCandidateList), "blockCount", documentBlocks.size(),
                                "blockLoadCostMillis", blockLoadCostMillis, "parentBuildCostMillis",
                                parentBuildCostMillis, "costMillis", chunkCostMillis));

                task.setCurrentStage(DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode());
                updateTaskState(task);

                long postProcessStartedNanos = System.nanoTime();
                List<ParentBlockCandidate> finalParentBlockList = parentBlockCandidateList.stream()
                        .filter(item -> item != null && StrUtil.isNotBlank(item.getText())
                                && item.getChildChunks() != null && item.getChildChunks().stream()
                                        .anyMatch(child -> StrUtil.isNotBlank(child.getText())))
                        .toList();
                long postProcessCostMillis = elapsedMillis(postProcessStartedNanos);
                log.info("切块后处理完成，documentId={}, taskId={}, parentCount={}, childCount={}, costMillis={}", documentId,
                        taskId, finalParentBlockList.size(), countChildCandidates(finalParentBlockList),
                        postProcessCostMillis);

                saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode(),
                        DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                        DocumentOperatorTypeEnum.SYSTEM.getCode(), null, "切块后处理完成，耗时 " + postProcessCostMillis + "ms。",
                        Map.of("parentCount", finalParentBlockList.size(), "childCount",
                                countChildCandidates(finalParentBlockList), "costMillis", postProcessCostMillis));

                if (chunkingContract == null || !chunkingContract.qa()) {
                    // 增强任务跑在独立线程池上，池在提交点捕获租户上下文（ChunkEnrichmentBatchExecutor），
                    // 所以这里必须已经在父文档租户作用域内。
                    DerivedRowTenantScope.runPerDocument(documentTenantId,
                            () -> applyChunkKeywordQuestionEnrichment(document, taskId, finalParentBlockList));
                }
                else {
                    validateQaOutput(finalParentBlockList, chunkingContract);
                    saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode(),
                            DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                            DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                            "QA profile source-preserving materialization.",
                            detail("chunkingContract", chunkingContract, "enrichmentPolicy", "PRESERVE_SOURCE"));
                }

                long parentChildEntityBuildStartedNanos = System.nanoTime();
                ParentChildEntityBundle entityBundle = buildParentChildEntities(documentId, taskId, planId,
                        finalParentBlockList);
                long parentChildEntityBuildCostMillis = elapsedMillis(parentChildEntityBuildStartedNanos);
                parentBlockEntityList = entityBundle.parentBlocks();
                chunkEntityList = entityBundle.childChunks();
                log.info("Parent/Child 实体构建完成，documentId={}, taskId={}, parentCount={}, chunkCount={}, costMillis={}",
                        documentId, taskId, parentBlockEntityList.size(), chunkEntityList.size(),
                        parentChildEntityBuildCostMillis);

                long parentChildPersistStartedNanos = System.nanoTime();
                DerivedRowTenantScope.runPerDocument(documentTenantId, () -> {
                    MybatisBatchExecutor.insertBatch(SuperAgentDocumentParentBlock.class, parentBlockEntityList);
                    MybatisBatchExecutor.insertBatch(SuperAgentDocumentChunk.class, chunkEntityList);
                });
                long parentChildPersistCostMillis = elapsedMillis(parentChildPersistStartedNanos);
                log.info("Parent/Child 元数据入库完成，documentId={}, taskId={}, parentCount={}, chunkCount={}, costMillis={}",
                        documentId, taskId, parentBlockEntityList.size(), chunkEntityList.size(),
                        parentChildPersistCostMillis);

                task.setCurrentStage(DocumentTaskStageEnum.VECTORIZE.getCode());
                updateTaskState(task);

                int embeddingBatchSize = embeddingBatchSize();
                saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.VECTORIZE.getCode(),
                        DocumentTaskEventTypeEnum.START.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                        DocumentOperatorTypeEnum.SYSTEM.getCode(), null, "开始执行向量化。",
                        detail("chunkCount", chunkEntityList.size(), "embeddingBatchSize", embeddingBatchSize,
                                "embeddingBatchCount", batchCount(chunkEntityList.size(), embeddingBatchSize),
                                "vectorStoreType", DocumentVectorStoreTypeEnum.PG_VECTOR.getMsg(), "parentCount",
                                parentBlockEntityList.size()));
                log.info(
                        "开始执行原文 chunk 向量化阶段，documentId={}, taskId={}, chunkCount={}, parentCount={}, embeddingBatchSize={}, embeddingBatchCount={}",
                        documentId, taskId, chunkEntityList.size(), parentBlockEntityList.size(), embeddingBatchSize,
                        batchCount(chunkEntityList.size(), embeddingBatchSize));

                long vectorStartedNanos = System.nanoTime();
                DerivedRowTenantScope.runPerDocument(documentTenantId, () -> vectorGateway.vectorize(chunkEntityList));
                long vectorCostMillis = elapsedMillis(vectorStartedNanos);
                long chunkUpdateStartedNanos = System.nanoTime();
                DerivedRowTenantScope.runPerDocument(documentTenantId,
                        () -> MybatisBatchExecutor.updateBatchById(SuperAgentDocumentChunk.class, chunkEntityList));
                long chunkUpdateCostMillis = elapsedMillis(chunkUpdateStartedNanos);
                log.info(
                        "原文 chunk 向量化阶段完成，documentId={}, taskId={}, chunkCount={}, vectorCostMillis={}, chunkUpdateCostMillis={}",
                        documentId, taskId, chunkEntityList.size(), vectorCostMillis, chunkUpdateCostMillis);

                saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.VECTORIZE.getCode(),
                        DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                        DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                        "向量化完成，向量写入耗时 " + vectorCostMillis + "ms，chunk 状态更新耗时 " + chunkUpdateCostMillis + "ms。",
                        detail("chunkCount", chunkEntityList.size(), "embeddingBatchSize", embeddingBatchSize,
                                "embeddingBatchCount", batchCount(chunkEntityList.size(), embeddingBatchSize),
                                "vectorStoreType", DocumentVectorStoreTypeEnum.PG_VECTOR.getMsg(), "parentCount",
                                parentBlockEntityList.size(), "vectorCostMillis", vectorCostMillis,
                                "chunkUpdateCostMillis", chunkUpdateCostMillis));

                DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
                task.setCurrentStage(DocumentTaskStageEnum.KEYWORD_INDEX.getCode());
                updateTaskState(task);
                saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.KEYWORD_INDEX.getCode(),
                        DocumentTaskEventTypeEnum.START.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                        DocumentOperatorTypeEnum.SYSTEM.getCode(), null, "开始构建关键词索引。",
                        detail("chunkCount", chunkEntityList.size(), "enabled", keywordSearchGateway != null));
                log.info("开始构建关键词索引，documentId={}, taskId={}, chunkCount={}, enabled={}", documentId, taskId,
                        chunkEntityList.size(), keywordSearchGateway != null);
                long keywordStartedNanos = System.nanoTime();
                if (keywordSearchGateway != null) {
                    DerivedRowTenantScope.runPerDocument(documentTenantId,
                            () -> keywordSearchGateway.indexChunks(chunkEntityList));
                }
                long keywordCostMillis = elapsedMillis(keywordStartedNanos);
                log.info("关键词索引阶段完成，documentId={}, taskId={}, chunkCount={}, enabled={}, costMillis={}", documentId,
                        taskId, chunkEntityList.size(), keywordSearchGateway != null, keywordCostMillis);
                saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.KEYWORD_INDEX.getCode(),
                        DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                        DocumentOperatorTypeEnum.SYSTEM.getCode(), null, "关键词索引完成，耗时 " + keywordCostMillis + "ms。",
                        detail("chunkCount", chunkEntityList.size(), "enabled", keywordSearchGateway != null,
                                "costMillis", keywordCostMillis));

                task.setCurrentStage(DocumentTaskStageEnum.GRAPH_RAG.getCode());
                updateTaskState(task);
                saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.GRAPH_RAG.getCode(),
                        DocumentTaskEventTypeEnum.START.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                        DocumentOperatorTypeEnum.SYSTEM.getCode(), null, "开始构建 GraphRAG 实体关系图谱。",
                        detail("chunkCount", chunkEntityList.size(), "parentCount", parentBlockEntityList.size()));
                log.info("开始构建 GraphRAG 实体关系图谱，documentId={}, taskId={}, chunkCount={}, parentCount={}", documentId,
                        taskId, chunkEntityList.size(), parentBlockEntityList.size());

                long graphRagStartedNanos = System.nanoTime();
                // 图谱抽取与入库是按文档的；其内部的跨文档投影重建由
                // GraphRagCrossDocumentIndexServiceImpl 自己声明系统上下文（见 runCrossTenant）。
                graphRagBuildResult = DerivedRowTenantScope.callPerDocument(documentTenantId,
                        () -> graphRagBuildService.rebuildDocumentGraph(documentId, taskId, chunkEntityList));
                long graphRagCostMillis = elapsedMillis(graphRagStartedNanos);
                log.info(
                        "GraphRAG 构建阶段完成，documentId={}, taskId={}, entityCount={}, relationCount={}, evidenceCount={}, communityCount={}, costMillis={}",
                        documentId, taskId, graphRagBuildResult.getEntityCount(),
                        graphRagBuildResult.getRelationCount(), graphRagBuildResult.getEvidenceCount(),
                        graphRagBuildResult.getCommunityCount(), graphRagCostMillis);

                saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.GRAPH_RAG.getCode(),
                        DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                        DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                        "GraphRAG 实体关系图谱构建完成，耗时 " + graphRagCostMillis + "ms。",
                        detail("entityCount", graphRagBuildResult.getEntityCount(), "relationCount",
                                graphRagBuildResult.getRelationCount(), "evidenceCount",
                                graphRagBuildResult.getEvidenceCount(), "communityCount",
                                graphRagBuildResult.getCommunityCount(), "costMillis", graphRagCostMillis));
            }

            GraphRagFinalization graphFinalization = finalizeGraphRagOutcome(document, documentId, taskId, planId, task,
                    documentTenantId, chunkEntityList, graphRagBuildResult, resumeCommittedGraph);
            graphRagBuildResult = graphFinalization.result();
            List<SuperAgentDocumentChunk> graphTypedChunkList = graphFinalization.typedChunks();
            if (graphRagBuildResult
                    .getOuterTaskDisposition() == GraphRagBuildResult.OuterTaskDisposition.REPAIR_REQUIRED) {
                task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
                task.setCurrentStage(DocumentTaskStageEnum.GRAPH_TYPED_INDEX.getCode());
                updateTaskState(task);
                progressCacheService.update(document, task);
                log.warn(
                        "GraphRAG post-commit component requires repair; BUILD_INDEX remains RUNNING: documentId={}, taskId={}, typedOutcome={}, crossOutcome={}, observationOutcome={}",
                        documentId, taskId, graphRagBuildResult.getTypedIndexOutcome(),
                        graphRagBuildResult.getCrossDocumentIndexOutcome(),
                        graphRagBuildResult.getObservationProjectionOutcome());
                return;
            }
            if (graphRagBuildResult
                    .getOuterTaskDisposition() == GraphRagBuildResult.OuterTaskDisposition.FAIL_INDEX_TASK) {
                applyGraphFailureDisposition(document, task, planId, startTime, graphRagBuildResult, null);
                return;
            }

            task.setCurrentStage(DocumentTaskStageEnum.RAPTOR.getCode());
            updateTaskState(task);
            saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.RAPTOR.getCode(),
                    DocumentTaskEventTypeEnum.START.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                    DocumentOperatorTypeEnum.SYSTEM.getCode(), null, "开始构建 RAPTOR 层级摘要树。",
                    detail("chunkCount", chunkEntityList.size(), "parentCount", parentBlockEntityList.size()));
            log.info("开始构建 RAPTOR 层级摘要树，documentId={}, taskId={}, chunkCount={}, parentCount={}", documentId, taskId,
                    chunkEntityList.size(), parentBlockEntityList.size());

            long raptorStartedNanos = System.nanoTime();
            RaptorBuildResult raptorBuildResult = DerivedRowTenantScope.callPerDocument(documentTenantId,
                    () -> raptorBuildService.rebuildDocumentTree(documentId, taskId, chunkEntityList));
            long raptorCostMillis = elapsedMillis(raptorStartedNanos);
            log.info(
                    "RAPTOR 构建阶段完成，documentId={}, taskId={}, nodeCount={}, levelCount={}, sourceChunkCount={}, costMillis={}",
                    documentId, taskId, raptorBuildResult.getNodeCount(), raptorBuildResult.getLevelCount(),
                    raptorBuildResult.getSourceChunkCount(), raptorCostMillis);

            saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.RAPTOR.getCode(),
                    DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                    DocumentOperatorTypeEnum.SYSTEM.getCode(), null, "RAPTOR 层级摘要树构建完成，耗时 " + raptorCostMillis + "ms。",
                    detail("nodeCount", raptorBuildResult.getNodeCount(), "levelCount",
                            raptorBuildResult.getLevelCount(), "sourceChunkCount",
                            raptorBuildResult.getSourceChunkCount(), "sourceQualityReport",
                            raptorBuildResult.getSourceQualityReport(), "savedQualityReport",
                            raptorBuildResult.getSavedQualityReport(), "costMillis", raptorCostMillis));

            task.setCurrentStage(DocumentTaskStageEnum.STORE_COMPLETE.getCode());
            updateTaskState(task);

            plan.setPlanStatus(DocumentPlanStatusEnum.EXECUTED.getCode());
            planMapper.updateById(plan);

            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode());
            document.setLastIndexTaskId(taskId);
            documentMapper.updateById(document);
            vectorGateway.tombstoneStaleTasks(documentId, taskId);
            DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
            if (keywordSearchGateway != null) {
                keywordSearchGateway.tombstoneStaleTasks(documentId, taskId);
            }

            finishTaskSuccess(task, DocumentTaskStageEnum.STORE_COMPLETE.getCode(), startTime);
            progressCacheService.update(document, task);
            saveIndexBuildLog(taskId, documentId, DocumentTaskStageEnum.STORE_COMPLETE.getCode(),
                    DocumentTaskEventTypeEnum.COMPLETE.getCode(), DocumentLogLevelEnum.INFO.getCode(),
                    DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                    "索引构建完成，总耗时 " + elapsedMillis(buildStartedNanos) + "ms。",
                    Map.of("taskId", taskId, "chunkCount", chunkEntityList.size(), "graphTypedChunkCount",
                            graphTypedChunkList.size(), "parentCount", parentBlockEntityList.size(), "costMillis",
                            elapsedMillis(buildStartedNanos)));
            log.info(
                    "索引构建任务执行完成，documentId={}, taskId={}, planId={}, parentCount={}, chunkCount={}, graphTypedChunkCount={}, costMillis={}",
                    documentId, taskId, planId, parentBlockEntityList.size(), chunkEntityList.size(),
                    graphTypedChunkList.size(), elapsedMillis(buildStartedNanos));
        }
        catch (GraphRagBuildStoppedException exception) {
            log.warn("GraphRAG 执行已停止，任务状态交由当前持有者处理: documentId={}, taskId={}, reason={}, extractorMetadata={}",
                    documentId, taskId, exception.reason(), exception.metadata());
        }
        catch (GraphRagBuildFailureException exception) {
            GraphRagBuildResult failureResult = exception.getResult() == null ? GraphRagBuildResult.builder()
                    .graphPersistenceOutcome(GraphRagBuildResult.GraphPersistenceOutcome.FAILED).kgCommitted(false)
                    .build() : exception.getResult();
            GraphRagBuildResult terminalResult = graphRagOutcomePolicy.finalizeOuterDisposition(failureResult,
                    GraphRagBuildResult.ComponentOutcome.NOT_APPLICABLE,
                    GraphRagBuildResult.ObservationProjectionOutcome.SUCCESS);
            try {
                graphRagBuildCheckpointService.markOutcome(documentId, taskId, terminalResult,
                        resultAttempt(terminalResult), resultMaxAttempts(terminalResult));
            }
            catch (RuntimeException observationFailure) {
                terminalResult = graphRagOutcomePolicy.finalizeOuterDisposition(failureResult,
                        GraphRagBuildResult.ComponentOutcome.NOT_APPLICABLE,
                        GraphRagBuildResult.ObservationProjectionOutcome.FAILED);
            }
            applyGraphFailureDisposition(document, task, planId, startTime, terminalResult, exception);
        }
        catch (Exception exception) {
            graphRagBuildResult = withdrawPendingCrossDocumentProjection(document, taskId, graphRagBuildResult);
            long failedCostMillis = elapsedMillis(buildStartedNanos);
            Integer failedStage = task.getCurrentStage() == null ? DocumentTaskStageEnum.CHUNK_EXECUTE.getCode()
                    : task.getCurrentStage();
            log.error("异步构建索引失败，documentId={}, taskId={}, planId={}, currentStage={}, costMillis={}", documentId,
                    taskId, planId, stageLabel(failedStage), failedCostMillis, exception);

            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_FAILED.getCode());
            documentMapper.updateById(document);

            if (Objects.equals(failedStage, DocumentTaskStageEnum.VECTORIZE.getCode())) {
                chunkMapper.update(null,
                        new LambdaUpdateWrapper<SuperAgentDocumentChunk>()
                                .eq(SuperAgentDocumentChunk::getTaskId, taskId)
                                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                                .set(SuperAgentDocumentChunk::getVectorStatus,
                                        DocumentVectorStatusEnum.VECTOR_FAILED.getCode())
                                .set(SuperAgentDocumentChunk::getVectorStoreType,
                                        DocumentVectorStoreTypeEnum.PG_VECTOR.getCode()));
            }

            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTE_FAILED.getCode());
            failTask(task, startTime, exception, failedStage);
            progressCacheService.update(document, task);
            saveIndexBuildLog(taskId, documentId, failedStage, DocumentTaskEventTypeEnum.FAILED.getCode(),
                    DocumentLogLevelEnum.ERROR.getCode(), DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                    "索引构建失败，当前阶段 " + stageLabel(failedStage) + "，已耗时 " + failedCostMillis + "ms。",
                    detail("error", exception.getMessage(), "currentStage", failedStage, "currentStageName",
                            stageName(failedStage), "costMillis", failedCostMillis));
        }
    }

    private GraphRagFinalization finalizeGraphRagOutcome(SuperAgentDocument document, Long documentId, Long taskId,
            Long planId, SuperAgentDocumentTask task, Long documentTenantId,
            List<SuperAgentDocumentChunk> sourceChunks,
            GraphRagBuildResult buildResult, boolean resumeCommittedGraph) {
        if (buildResult == null || buildResult.getGraphPersistenceOutcome() == null) {
            throw new IllegalStateException("GraphRAG build did not return an explicit persistence outcome.");
        }

        List<SuperAgentDocumentChunk> typedChunks = List.of();
        GraphRagBuildResult.ComponentOutcome typedOutcome;
        boolean kgCommitted = Boolean.TRUE.equals(buildResult.getKgCommitted());
        boolean graphEmpty = buildResult
                .getGraphPersistenceOutcome() == GraphRagBuildResult.GraphPersistenceOutcome.EMPTY;
        if (!kgCommitted
                || buildResult.getGraphPersistenceOutcome() == GraphRagBuildResult.GraphPersistenceOutcome.FAILED) {
            typedOutcome = GraphRagBuildResult.ComponentOutcome.NOT_APPLICABLE;
        }
        else {
            List<SuperAgentDocumentChunk> existingTypedChunks = listFrozenTypedChunks(documentId, taskId);
            boolean reuseSuccessfulTyped = resumeCommittedGraph
                    && buildResult.getTypedIndexOutcome() == GraphRagBuildResult.ComponentOutcome.SUCCESS
                    && !existingTypedChunks.isEmpty();
            boolean reuseEmptyTyped = resumeCommittedGraph && graphEmpty
                    && buildResult.getTypedIndexOutcome() == GraphRagBuildResult.ComponentOutcome.NOT_APPLICABLE
                    && existingTypedChunks.isEmpty();
            if (reuseSuccessfulTyped) {
                typedChunks = existingTypedChunks;
                typedOutcome = GraphRagBuildResult.ComponentOutcome.SUCCESS;
            }
            else if (reuseEmptyTyped) {
                typedOutcome = GraphRagBuildResult.ComponentOutcome.NOT_APPLICABLE;
            }
            else {
                task.setCurrentStage(DocumentTaskStageEnum.GRAPH_TYPED_INDEX.getCode());
                updateTaskState(task);
                try {
                    // typed chunk 是文档的派生内容，写入必须在父文档租户作用域内（S22 批次 2）。
                    List<SuperAgentDocumentChunk> replaced = DerivedRowTenantScope.callPerDocument(
                            documentTenantId,
                            () -> graphRagTypedChunkService.replaceTypedIndex(documentId, taskId, planId, sourceChunks,
                                    nextChunkNo(sourceChunks)));
                    typedChunks = replaced == null ? List.of() : replaced;
                    typedOutcome = graphEmpty && typedChunks.isEmpty()
                            ? GraphRagBuildResult.ComponentOutcome.NOT_APPLICABLE
                            : typedChunks.isEmpty() ? GraphRagBuildResult.ComponentOutcome.FAILED
                                    : GraphRagBuildResult.ComponentOutcome.SUCCESS;
                }
                catch (RuntimeException exception) {
                    log.warn(
                            "GraphRAG typed projection failed; preserving committed KG: documentId={}, taskId={}, message={}",
                            documentId, taskId, exception.getMessage());
                    typedChunks = List.of();
                    typedOutcome = GraphRagBuildResult.ComponentOutcome.FAILED;
                }
            }
        }

        GraphRagBuildResult candidate = graphRagOutcomePolicy.finalizeOuterDisposition(buildResult, typedOutcome,
                GraphRagBuildResult.ObservationProjectionOutcome.SUCCESS);
        if (candidate.getOuterTaskDisposition() == GraphRagBuildResult.OuterTaskDisposition.REPAIR_REQUIRED) {
            candidate = withdrawPendingCrossDocumentProjection(document, taskId, candidate);
        }
        try {
            graphRagBuildCheckpointService.markOutcome(documentId, taskId, candidate, resultAttempt(candidate),
                    resultMaxAttempts(candidate));
            return new GraphRagFinalization(candidate, typedChunks);
        }
        catch (RuntimeException exception) {
            log.warn(
                    "GraphRAG final outcome projection failed; BUILD_INDEX remains repairable: documentId={}, taskId={}, message={}",
                    documentId, taskId, exception.getMessage());
            GraphRagBuildResult failedObservation = graphRagOutcomePolicy.finalizeOuterDisposition(buildResult,
                    typedOutcome, GraphRagBuildResult.ObservationProjectionOutcome.FAILED);
            failedObservation = withdrawPendingCrossDocumentProjection(document, taskId, failedObservation);
            return new GraphRagFinalization(failedObservation, typedChunks);
        }
    }

    private GraphRagBuildResult repairCrossDocumentProjection(SuperAgentDocument document, Long documentId, Long taskId,
            GraphRagBuildResult buildResult) {
        boolean alreadyActive = document != null && Objects.equals(document.getLastIndexTaskId(), taskId);
        if (alreadyActive
                && buildResult.getCrossDocumentIndexOutcome() == GraphRagBuildResult.ComponentOutcome.SUCCESS) {
            return buildResult;
        }
        try {
            crossDocumentIndexService.rebuildAll(documentId, taskId);
            return graphRagOutcomePolicy.withCrossDocumentOutcome(buildResult,
                    GraphRagBuildResult.ComponentOutcome.SUCCESS);
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG cross-document repair failed: documentId={}, taskId={}, message={}", documentId, taskId,
                    exception.getMessage());
            return graphRagOutcomePolicy.withCrossDocumentOutcome(buildResult,
                    GraphRagBuildResult.ComponentOutcome.FAILED);
        }
    }

    private GraphRagBuildResult withdrawPendingCrossDocumentProjection(SuperAgentDocument document, Long taskId,
            GraphRagBuildResult buildResult) {
        if (buildResult == null
                || buildResult.getCrossDocumentIndexOutcome() != GraphRagBuildResult.ComponentOutcome.SUCCESS
                || document == null || Objects.equals(document.getLastIndexTaskId(), taskId)) {
            return buildResult;
        }
        try {
            crossDocumentIndexService.rebuildAll();
            log.info(
                    "GraphRAG pending cross-document projection withdrawn to active document pointers: documentId={}, taskId={}",
                    document.getId(), taskId);
        }
        catch (RuntimeException exception) {
            log.error(
                    "GraphRAG pending cross-document projection withdrawal failed; task cannot publish current I: documentId={}, taskId={}, message={}",
                    document.getId(), taskId, exception.getMessage(), exception);
        }
        return graphRagOutcomePolicy.withCrossDocumentOutcome(buildResult, GraphRagBuildResult.ComponentOutcome.FAILED);
    }

    private List<SuperAgentDocumentChunk> listFrozenSourceChunks(Long documentId, Long taskId) {
        List<SuperAgentDocumentChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getDocumentId, documentId).eq(SuperAgentDocumentChunk::getTaskId, taskId)
                .ne(SuperAgentDocumentChunk::getSourceType, DocumentChunkSourceTypeEnum.GRAPH_RAG.getCode())
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentChunk::getChunkNo).orderByAsc(SuperAgentDocumentChunk::getId));
        return chunks == null ? List.of() : chunks;
    }

    private List<SuperAgentDocumentChunk> listFrozenTypedChunks(Long documentId, Long taskId) {
        List<SuperAgentDocumentChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getDocumentId, documentId).eq(SuperAgentDocumentChunk::getTaskId, taskId)
                .eq(SuperAgentDocumentChunk::getSourceType, DocumentChunkSourceTypeEnum.GRAPH_RAG.getCode())
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentChunk::getChunkNo).orderByAsc(SuperAgentDocumentChunk::getId));
        return chunks == null ? List.of() : chunks;
    }

    private GraphRagBuildResult readGraphRagBuildResult(SuperAgentDocumentTask task) {
        if (task == null || StrUtil.isBlank(task.getExtJson())) {
            return null;
        }
        try {
            Object rawState = readTaskExtJson(task.getExtJson()).get("graphRagBuild");
            if (!(rawState instanceof Map<?, ?> state)) {
                return null;
            }
            GraphRagBuildResult.GraphPersistenceOutcome persistenceOutcome = enumValue(
                    GraphRagBuildResult.GraphPersistenceOutcome.class, state.get("graphPersistenceOutcome"));
            Boolean kgCommitted = booleanValue(state.get("kgCommitted"));
            if (persistenceOutcome == null || kgCommitted == null) {
                return null;
            }
            return GraphRagBuildResult.builder().entityCount(integerValue(state.get("entityCount")))
                    .relationCount(integerValue(state.get("relationCount")))
                    .evidenceCount(integerValue(state.get("evidenceCount")))
                    .communityCount(integerValue(state.get("communityCount")))
                    .graphPersistenceOutcome(persistenceOutcome)
                    .graphPersistenceReason(stringValue(state.get("graphPersistenceReason"))).kgCommitted(kgCommitted)
                    .typedIndexOutcome(
                            enumValue(GraphRagBuildResult.ComponentOutcome.class, state.get("typedIndexOutcome")))
                    .crossDocumentIndexOutcome(enumValue(GraphRagBuildResult.ComponentOutcome.class,
                            state.get("crossDocumentIndexOutcome")))
                    .derivedIndexOutcome(
                            enumValue(GraphRagBuildResult.DerivedIndexOutcome.class, state.get("derivedIndexOutcome")))
                    .observationProjectionOutcome(enumValue(GraphRagBuildResult.ObservationProjectionOutcome.class,
                            state.get("observationProjectionOutcome")))
                    .outerTaskDisposition(enumValue(GraphRagBuildResult.OuterTaskDisposition.class,
                            state.get("outerTaskDisposition")))
                    .pythonInvocationOutcome(enumValue(GraphRagBuildResult.InvocationOutcome.class,
                            state.get("pythonInvocationOutcome")))
                    .advisorInvocationOutcome(enumValue(GraphRagBuildResult.InvocationOutcome.class,
                            state.get("advisorInvocationOutcome")))
                    .pythonExtractionStatus(stringValue(state.get("pythonExtractionStatus")))
                    .advisorReason(stringValue(state.get("advisorReason")))
                    .degradationReasons(stringListValue(state.get("degradationReasons")))
                    .extractionMetadata(stringObjectMap(state.get("extractorMetadata")))
                    .attempt(integerValue(state.get("attempt"))).maxAttempts(integerValue(state.get("maxAttempts")))
                    .build();
        }
        catch (Exception exception) {
            log.warn("Ignoring unreadable GraphRAG outcome checkpoint: taskId={}, message={}", task.getId(),
                    exception.getMessage());
            return null;
        }
    }

    private boolean isCommittedGraph(GraphRagBuildResult result) {
        return result != null && Boolean.TRUE.equals(result.getKgCommitted())
                && result.getGraphPersistenceOutcome() != null
                && result.getGraphPersistenceOutcome() != GraphRagBuildResult.GraphPersistenceOutcome.FAILED;
    }

    private void applyGraphFailureDisposition(SuperAgentDocument document, SuperAgentDocumentTask task, Long planId,
            Date startTime, GraphRagBuildResult result, Exception cause) {
        Exception failure = cause == null ? new IllegalStateException(result == null ? "GraphRAG build failed."
                : StrUtil.blankToDefault(result.getGraphPersistenceReason(), "GraphRAG build failed.")) : cause;
        Integer failedStage = task.getCurrentStage() == null ? DocumentTaskStageEnum.GRAPH_RAG.getCode()
                : task.getCurrentStage();
        document.setIndexStatus(DocumentIndexStatusEnum.BUILD_FAILED.getCode());
        documentMapper.updateById(document);
        updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTE_FAILED.getCode());
        failTask(task, startTime, failure, failedStage);
        progressCacheService.update(document, task);
        saveIndexBuildLog(task.getId(), document.getId(), failedStage, DocumentTaskEventTypeEnum.FAILED.getCode(),
                DocumentLogLevelEnum.ERROR.getCode(), DocumentOperatorTypeEnum.SYSTEM.getCode(), null,
                "GraphRAG 构建失败，已保留任务失败状态和当轮诊断。",
                detail("errorCode", task.getErrorCode(), "currentStage", failedStage, "currentStageName",
                        stageName(failedStage), "failureDiagnostic",
                        failureDiagnosticProjector.graphFailure(failure, document.getId(), task.getId(),
                                stageName(failedStage), "BUILD_INDEX")));
    }

    private int resultAttempt(GraphRagBuildResult result) {
        return result == null || result.getAttempt() == null ? 0 : Math.max(0, result.getAttempt());
    }

    private int resultMaxAttempts(GraphRagBuildResult result) {
        return result == null || result.getMaxAttempts() == null ? Math.max(1, resultAttempt(result))
                : Math.max(1, result.getMaxAttempts());
    }

    private <E extends Enum<E>> E enumValue(Class<E> enumType, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, String.valueOf(value));
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Boolean booleanValue(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> stringListValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private Map<String, Object> stringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key instanceof String text) {
                result.put(text, item);
            }
        });
        return result;
    }

    private record GraphRagFinalization(GraphRagBuildResult result, List<SuperAgentDocumentChunk> typedChunks) {
    }

    private void saveIndexBuildLog(Long taskId, Long documentId, Integer stageType, Integer eventType, Integer logLevel,
            Integer operatorType, Long operatorId, String content, Object detail) {
        SuperAgentDocumentTaskLog taskLog = taskLogService.saveLog(taskId, documentId, stageType, eventType, logLevel,
                operatorType, operatorId, content, detail);
        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        progressCacheService.update(document, task, taskLog);
    }

    private void saveParseRouteLog(Long taskId, Long documentId, Integer stageType, Integer eventType, Integer logLevel,
            Integer operatorType, Long operatorId, String content, Object detail) {
        saveParseRouteLog(taskId, documentId, stageType, eventType, logLevel, operatorType, operatorId, content, detail,
                null, List.of());
    }

    private void saveParseRouteLog(Long taskId, Long documentId, Integer stageType, Integer eventType, Integer logLevel,
            Integer operatorType, Long operatorId, String content, Object detail, SuperAgentDocumentStrategyPlan plan,
            List<SuperAgentDocumentStrategyStep> steps) {
        SuperAgentDocumentTaskLog taskLog = taskLogService.saveLog(taskId, documentId, stageType, eventType, logLevel,
                operatorType, operatorId, content, detail);
        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        parseRouteProgressCacheService.update(document, task, plan, steps, taskLog);
    }

    /**
     * 构建期关键词与问题增强（LLM 升级）。
     *
     * <p>由数据库系统配置 {@code chunkEnrichment.enabled} 控制：advisor 始终装配，开启时用 LLM 受控生成 + Java 校验后的
     * keywords/questions 覆盖切块期启发式值，并同步重算加权正文的 {@code [KEYWORDS]/[QUESTIONS]} 段；关闭时 advisor 返回空结果，
     * 直接保留启发式值。单个切块调用失败 / 未产出时也保留启发式值（错误隔离，非旧链路兜底、行为零回归）。</p>
     */
    private void applyChunkKeywordQuestionEnrichment(SuperAgentDocument document, Long taskId,
            List<ParentBlockCandidate> parentBlockCandidateList) {
        if (document == null || parentBlockCandidateList == null || parentBlockCandidateList.isEmpty()) {
            return;
        }
        LlmChunkKeywordQuestionAdvisor advisor = chunkKeywordQuestionAdvisorProvider.getIfAvailable();
        if (advisor == null) {
            // 测试或最小化装配时没有 advisor，保留切块期启发式值。
            return;
        }

        Map<String, ChunkCandidate> refToCandidate = new LinkedHashMap<>();
        List<LlmChunkKeywordQuestionAdvisor.ChunkEnrichmentItem> items = new ArrayList<>();
        int refSeq = 0;
        for (ParentBlockCandidate parent : parentBlockCandidateList) {
            if (parent == null || parent.getChildChunks() == null) {
                continue;
            }
            for (ChunkCandidate child : parent.getChildChunks()) {
                if (child == null || StrUtil.isBlank(child.getText())) {
                    continue;
                }
                String ref = String.valueOf(refSeq++);
                refToCandidate.put(ref, child);
                items.add(new LlmChunkKeywordQuestionAdvisor.ChunkEnrichmentItem(ref, child.getText(), child.getTitle(),
                        child.getSectionPath(), child.getChunkType()));
            }
        }
        if (items.isEmpty()) {
            return;
        }

        log.info("关键词与问题增强开始: documentId={}, taskId={}, chunkCount={}",
                document.getId(), taskId, items.size());
        Map<String, LlmChunkKeywordQuestionAdvisor.ChunkKeywordQuestion> enriched =
                advisor.enrich(document.getId(), taskId, items);
        if (enriched.isEmpty()) {
            log.info("关键词与问题增强未产出可用结果，全部保留启发式值: documentId={}, taskId={}, chunkCount={}",
                    document.getId(), taskId, items.size());
            return;
        }

        int keywordUpdated = 0;
        int questionUpdated = 0;
        for (Map.Entry<String, LlmChunkKeywordQuestionAdvisor.ChunkKeywordQuestion> entry : enriched.entrySet()) {
            ChunkCandidate candidate = refToCandidate.get(entry.getKey());
            if (candidate == null) {
                continue;
            }
            LlmChunkKeywordQuestionAdvisor.ChunkKeywordQuestion advice = entry.getValue();
            boolean changed = false;
            if (advice.keywords() != null && !advice.keywords().isEmpty()) {
                candidate.setKeywords(writeStringListJson(advice.keywords()));
                keywordUpdated++;
                changed = true;
            }
            if (advice.questions() != null && !advice.questions().isEmpty()) {
                candidate.setQuestions(writeStringListJson(advice.questions()));
                questionUpdated++;
                changed = true;
            }
            if (changed) {
                candidate.setContentWithWeight(strategyService.rebuildContentWithWeight(candidate,
                        candidate.getKeywords(), candidate.getQuestions()));
            }
        }
        log.info(
                "关键词与问题增强完成: documentId={}, taskId={}, chunkCount={}, keywordUpdated={}, questionUpdated={}",
                document.getId(), taskId, items.size(), keywordUpdated, questionUpdated);
    }

    private String writeStringListJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        }
        catch (Exception exception) {
            throw new IllegalStateException("序列化 chunk 检索增强字段失败。", exception);
        }
    }

    private void validateQaOutput(List<ParentBlockCandidate> parents, ChunkingContract contract) {
        if (parents.isEmpty()) {
            throw new ChunkingProfileException("PROFILE_EMPTY_OUTPUT", null);
        }
        for (ParentBlockCandidate parent : parents) {
            if (parent.getSourceProvenance() == null || parent.getChildChunks().isEmpty()) {
                throw new ChunkingProfileException("PROFILE_SOURCE_MAPPING_INVALID", null);
            }
            for (ChunkCandidate child : parent.getChildChunks()) {
                ChunkSourceProvenance source = child.getSourceProvenance();
                if (source == null || !Objects.equals(source.pairId(), parent.getSourceProvenance().pairId())
                        || !Objects.equals(source.sourceParseTaskId(), contract.sourceParseTaskId())
                        || !Objects.equals(source.sourceSha256(), contract.sourceSha256())
                        || !Objects.equals(source.ruleVersion(), contract.ruleVersion())) {
                    throw new ChunkingProfileException("PROFILE_SOURCE_MAPPING_INVALID", null);
                }
                if (child.getContentWithWeight().getBytes(StandardCharsets.UTF_8).length > contract.budget()
                        .maxInputBytes()) {
                    throw new ChunkingProfileException("PROFILE_UNIT_TOO_LARGE", source.pairId());
                }
            }
        }
    }

    private ParentChildEntityBundle buildParentChildEntities(Long documentId, Long taskId, Long planId,
            List<ParentBlockCandidate> parentBlockCandidateList) {
        List<SuperAgentDocumentParentBlock> parentBlockEntityList = new ArrayList<>();
        List<SuperAgentDocumentChunk> chunkEntityList = new ArrayList<>();
        int globalChunkNo = 1;

        for (int parentIndex = 0; parentIndex < parentBlockCandidateList.size(); parentIndex++) {
            ParentBlockCandidate parentCandidate = parentBlockCandidateList.get(parentIndex);
            if (parentCandidate == null || StrUtil.isBlank(parentCandidate.getText())) {
                continue;
            }

            SuperAgentDocumentParentBlock parentBlock = new SuperAgentDocumentParentBlock();
            parentBlock.setId(uidGenerator.getUid());
            parentBlock.setDocumentId(documentId);
            parentBlock.setTaskId(taskId);
            parentBlock.setPlanId(planId);
            parentBlock.setParentNo(parentIndex + 1);
            parentBlock.setSourceType(
                    parentCandidate.getSourceType() == null ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode()
                            : parentCandidate.getSourceType());
            parentBlock.setSectionPath(parentCandidate.getSectionPath());
            parentBlock.setStructureNodeId(parentCandidate.getStructureNodeId());
            parentBlock.setStructureNodeType(parentCandidate.getStructureNodeType());
            parentBlock.setCanonicalPath(parentCandidate.getCanonicalPath());
            parentBlock.setItemIndex(parentCandidate.getItemIndex());
            parentBlock.setParentText(parentCandidate.getText().trim());
            if (parentCandidate.getSourceProvenance() != null) {
                parentBlock.setSourceProvenanceJson(parentCandidate.getSourceProvenance().json(objectMapper));
            }
            parentBlock.setCharCount(parentCandidate.getText().length());
            parentBlock.setTokenCount(estimateTokenCount(parentCandidate.getText()));
            parentBlock.setStatus(BusinessStatus.YES.getCode());

            int startChunkNo = globalChunkNo;
            int childCount = 0;
            for (ChunkCandidate childCandidate : parentCandidate.getChildChunks()) {
                if (childCandidate == null || StrUtil.isBlank(childCandidate.getText())) {
                    continue;
                }
                SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
                chunk.setId(uidGenerator.getUid());
                chunk.setDocumentId(documentId);
                chunk.setTaskId(taskId);
                chunk.setPlanId(planId);
                chunk.setParentBlockId(parentBlock.getId());
                chunk.setChunkNo(globalChunkNo++);
                chunk.setSourceType(
                        childCandidate.getSourceType() == null ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode()
                                : childCandidate.getSourceType());
                chunk.setSectionPath(
                        StrUtil.blankToDefault(childCandidate.getSectionPath(), parentCandidate.getSectionPath()));
                chunk.setStructureNodeId(childCandidate.getStructureNodeId());
                chunk.setStructureNodeType(childCandidate.getStructureNodeType());
                chunk.setCanonicalPath(childCandidate.getCanonicalPath());
                chunk.setItemIndex(childCandidate.getItemIndex());
                chunk.setChunkText(childCandidate.getText().trim());
                chunk.setContentWithWeight(
                        StrUtil.blankToDefault(childCandidate.getContentWithWeight(), childCandidate.getText()).trim());
                chunk.setChunkType(childCandidate.getChunkType());
                chunk.setTitle(childCandidate.getTitle());
                chunk.setKeywords(childCandidate.getKeywords());
                chunk.setQuestions(childCandidate.getQuestions());
                chunk.setCharCount(childCandidate.getText().length());

                chunk.setTokenCount(estimateTokenCount(childCandidate.getText()));
                chunk.setVectorStatus(DocumentVectorStatusEnum.WAIT_VECTOR.getCode());
                chunk.setVectorStoreType(DocumentVectorStoreTypeEnum.PG_VECTOR.getCode());
                chunk.setPageNo(childCandidate.getPageNo());
                chunk.setPageRange(childCandidate.getPageRange());
                chunk.setBboxJson(childCandidate.getBboxJson());
                chunk.setSourceBlockIds(childCandidate.getSourceBlockIds());
                if (childCandidate.getSourceProvenance() != null) {
                    chunk.setSourceProvenanceJson(childCandidate.getSourceProvenance().json(objectMapper));
                }
                chunk.setStatus(BusinessStatus.YES.getCode());
                chunkEntityList.add(chunk);
                childCount++;
            }

            parentBlock.setChildCount(childCount);
            parentBlock.setStartChunkNo(childCount == 0 ? null : startChunkNo);
            parentBlock.setEndChunkNo(childCount == 0 ? null : globalChunkNo - 1);
            parentBlock.setPageRange(parentCandidate.getPageRange());
            parentBlock.setSourceBlockIds(parentCandidate.getSourceBlockIds());
            parentBlockEntityList.add(parentBlock);
        }

        return new ParentChildEntityBundle(parentBlockEntityList, chunkEntityList);
    }

    private int nextChunkNo(List<SuperAgentDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 1;
        }
        return chunks.stream().map(SuperAgentDocumentChunk::getChunkNo).filter(Objects::nonNull).max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private void saveParseArtifactsAndBlocks(Long documentId, Long taskId, DocumentAnalysisResult analysisResult) {
        List<SuperAgentDocumentParseArtifact> artifacts = buildParseArtifactEntities(documentId, taskId,
                analysisResult == null ? List.of() : analysisResult.getParseArtifacts());
        List<SuperAgentDocumentBlock> blocks = buildDocumentBlockEntities(documentId, taskId,
                analysisResult == null ? List.of() : analysisResult.getBlocks());
        List<DocumentTableCandidate> tables = analysisResult == null || analysisResult.getTableCandidates() == null
                ? List.of()
                : analysisResult.getTableCandidates();
        parseArtifactService.replaceTaskArtifacts(documentId, taskId, artifacts, blocks, tables);
    }

    private List<SuperAgentDocumentParseArtifact> buildParseArtifactEntities(Long documentId, Long taskId,
            List<DocumentParseArtifactCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<SuperAgentDocumentParseArtifact> artifacts = new ArrayList<>();
        for (DocumentParseArtifactCandidate candidate : candidates) {
            if (candidate == null || StrUtil.isBlank(candidate.getArtifactType())
                    || StrUtil.isBlank(candidate.getContentBase64())) {
                continue;
            }
            byte[] content = decodeBase64(candidate.getContentBase64(), "解析产物 " + candidate.getFileName());
            String objectName = storageService.uploadParseArtifact(documentId, taskId,
                    StrUtil.blankToDefault(candidate.getFileName(), candidate.getArtifactType().toLowerCase() + ".bin"),
                    content, candidate.getContentType());

            SuperAgentDocumentParseArtifact artifact = new SuperAgentDocumentParseArtifact();
            artifact.setId(uidGenerator.getUid());
            artifact.setDocumentId(documentId);
            artifact.setTaskId(taskId);
            artifact.setArtifactType(candidate.getArtifactType());
            artifact.setObjectName(objectName);
            artifact.setContentHash(StrUtil.blankToDefault(candidate.getContentHash(), sha256(content)));
            artifact.setParserName(candidate.getParserName());
            artifact.setParserVersion(candidate.getParserVersion());
            artifact.setStatus(BusinessStatus.YES.getCode());
            artifacts.add(artifact);
        }
        return artifacts;
    }

    private List<SuperAgentDocumentBlock> buildDocumentBlockEntities(Long documentId, Long taskId,
            List<DocumentBlockCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<Integer, Long> idByBlockNo = new LinkedHashMap<>();
        for (DocumentBlockCandidate candidate : candidates) {
            if (candidate != null && candidate.getBlockNo() != null) {
                idByBlockNo.put(candidate.getBlockNo(), uidGenerator.getUid());
            }
        }

        List<SuperAgentDocumentBlock> blocks = new ArrayList<>();
        for (DocumentBlockCandidate candidate : candidates) {
            if (candidate == null || candidate.getBlockNo() == null || StrUtil.isBlank(candidate.getBlockType())) {
                continue;
            }
            SuperAgentDocumentBlock block = new SuperAgentDocumentBlock();
            block.setId(idByBlockNo.get(candidate.getBlockNo()));
            block.setDocumentId(documentId);
            block.setTaskId(taskId);
            block.setBlockNo(candidate.getBlockNo());
            block.setBlockType(candidate.getBlockType());
            block.setParentBlockId(
                    candidate.getParentBlockNo() == null ? null : idByBlockNo.get(candidate.getParentBlockNo()));
            block.setSectionPath(candidate.getSectionPath());
            block.setCanonicalPath(candidate.getCanonicalPath());
            block.setPageNo(candidate.getPageNo());
            block.setPageRange(candidate.getPageRange());
            block.setBboxJson(candidate.getBboxJson());
            block.setText(candidate.getText());
            block.setContentWithWeight(candidate.getContentWithWeight());
            block.setTableHtml(candidate.getTableHtml());
            block.setImageObjectName(uploadBlockImage(documentId, taskId, candidate));
            block.setImageCaption(candidate.getImageCaption());
            block.setMetadataJson(StrUtil.blankToDefault(candidate.getMetadataJson(), ""));
            block.setStatus(BusinessStatus.YES.getCode());
            blocks.add(block);
        }
        return blocks;
    }

    private String uploadBlockImage(Long documentId, Long taskId, DocumentBlockCandidate candidate) {
        if (candidate == null || StrUtil.isBlank(candidate.getImageContentBase64())) {
            return null;
        }
        byte[] content = decodeBase64(candidate.getImageContentBase64(), "block image " + candidate.getBlockNo());
        return storageService.uploadParseArtifact(documentId, taskId,
                StrUtil.blankToDefault(candidate.getImageFileName(), "block-" + candidate.getBlockNo() + ".png"),
                content, "image/png");
    }

    private int countChildCandidates(List<ParentBlockCandidate> parentBlockCandidateList) {
        if (parentBlockCandidateList == null || parentBlockCandidateList.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ParentBlockCandidate candidate : parentBlockCandidateList) {
            if (candidate == null || candidate.getChildChunks() == null) {
                continue;
            }
            count += (int) candidate.getChildChunks().stream()
                    .filter(child -> child != null && StrUtil.isNotBlank(child.getText())).count();
        }
        return count;
    }

    private void updateStepExecuteStatus(Long planId, Integer executeStatus) {

        stepMapper.update(null,
                new LambdaUpdateWrapper<SuperAgentDocumentStrategyStep>()
                        .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
                        .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode())
                        .set(SuperAgentDocumentStrategyStep::getExecuteStatus, executeStatus));
    }

    private List<SuperAgentDocumentStrategyStep> listSteps(Long planId) {
        List<SuperAgentDocumentStrategyStep> stepList = stepMapper
                .selectList(new LambdaQueryWrapper<SuperAgentDocumentStrategyStep>()
                        .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
                        .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode()));
        return stepList.stream()
                .sorted(Comparator
                        .comparingInt((SuperAgentDocumentStrategyStep step) -> pipelineOrder(step.getPipelineType()))
                        .thenComparing(SuperAgentDocumentStrategyStep::getStepNo)
                        .thenComparing(SuperAgentDocumentStrategyStep::getId))
                .toList();
    }

    private int pipelineOrder(String pipelineType) {
        return DocumentStrategyPipelineTypeEnum.PARENT.getCode()
                .equalsIgnoreCase(StrUtil.blankToDefault(pipelineType, "")) ? 0 : 1;
    }

    private int getNextPlanVersion(Long documentId) {

        List<SuperAgentDocumentStrategyPlan> planList = planMapper
                .selectList(new LambdaQueryWrapper<SuperAgentDocumentStrategyPlan>()
                        .eq(SuperAgentDocumentStrategyPlan::getDocumentId, documentId)
                        .eq(SuperAgentDocumentStrategyPlan::getStatus, BusinessStatus.YES.getCode())
                        .orderByDesc(SuperAgentDocumentStrategyPlan::getPlanVersion).last("limit 1"));
        return planList.isEmpty() ? 1 : planList.get(0).getPlanVersion() + 1;
    }

    private void finishTaskSuccess(SuperAgentDocumentTask task, Integer stage, Date startTime) {

        Date finishTime = new Date();
        task.setTaskStatus(DocumentTaskStatusEnum.SUCCESS.getCode());
        task.setCurrentStage(stage);
        task.setFinishTime(finishTime);
        task.setCostMillis(finishTime.getTime() - startTime.getTime());
        task.setErrorCode(null);
        task.setErrorMsg(null);
        updateTaskState(task);
    }

    private void syncNavigationArtifacts(Long documentId, Long parseTaskId,
            List<SuperAgentDocumentStructureNode> structureNodes) {
        long startedNanos = System.nanoTime();
        log.info("开始同步导航产物: documentId={}, parseTaskId={}, structureNodeCount={}", documentId, parseTaskId,
                structureNodes == null ? 0 : structureNodes.size());
        DocumentNavigationIndexService navigationIndexService = navigationIndexServiceProvider.getIfAvailable();
        if (navigationIndexService != null) {
            long navigationIndexStartedNanos = System.nanoTime();
            log.info("同步导航 ES 索引: documentId={}, parseTaskId={}", documentId, parseTaskId);
            navigationIndexService.reindexDocumentNodes(documentId, parseTaskId, structureNodes);
            log.info("同步导航 ES 索引完成: documentId={}, parseTaskId={}, costMillis={}", documentId, parseTaskId,
                    elapsedMillis(navigationIndexStartedNanos));
        }
        else {
            log.info("跳过导航 ES 索引同步，因为服务未启用: documentId={}, parseTaskId={}", documentId, parseTaskId);
        }
        DocumentStructureGraphProjectionService graphProjectionService = graphProjectionServiceProvider
                .getIfAvailable();
        if (graphProjectionService != null && graphProjectionService.enabled()) {
            long graphProjectionStartedNanos = System.nanoTime();
            log.info("同步结构图投影: documentId={}, parseTaskId={}", documentId, parseTaskId);
            graphProjectionService.projectToGraph(documentId, parseTaskId);
            log.info("同步结构图投影完成: documentId={}, parseTaskId={}, costMillis={}", documentId, parseTaskId,
                    elapsedMillis(graphProjectionStartedNanos));
        }
        else {
            log.info("跳过结构图投影，因为图服务未启用: documentId={}, parseTaskId={}", documentId, parseTaskId);
        }
        log.info("导航产物同步流程完成: documentId={}, parseTaskId={}, costMillis={}", documentId, parseTaskId,
                elapsedMillis(startedNanos));
    }

    private void updateTaskState(SuperAgentDocumentTask task) {
        // Ext JSON is owned by targeted contract/checkpoint updates. A loaded task can be stale.
        SuperAgentDocumentTask update = new SuperAgentDocumentTask();
        BeanUtils.copyProperties(task, update, "extJson");
        taskMapper.updateById(update);
    }

    private void failTask(SuperAgentDocumentTask task, Date startTime, Exception exception, Integer currentStage) {

        Date finishTime = new Date();
        task.setTaskStatus(DocumentTaskStatusEnum.FAILED.getCode());
        task.setCurrentStage(currentStage);
        task.setFinishTime(finishTime);
        task.setCostMillis(finishTime.getTime() - startTime.getTime());
        task.setErrorCode(exception instanceof ChunkingProfileException profileFailure ? profileFailure.getCode()
                : "TASK_FAILED");
        task.setErrorMsg(exception.getMessage());
        updateTaskState(task);
    }

    private int estimateTokenCount(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        int chineseCount = 0;
        int englishCount = 0;

        for (char current : text.toCharArray()) {
            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                chineseCount++;
            }
        }

        for (String word : text.split("\\s+")) {
            if (word.matches(".*[A-Za-z].*")) {
                englishCount++;
            }
        }

        return chineseCount + englishCount + Math.max(1, (text.length() - chineseCount) / 4);
    }

    private Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> detailMap = new LinkedHashMap<>();

        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            detailMap.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return detailMap;
    }

    private String parseModeCode(Integer fileTypeCode) {
        DocumentFileTypeEnum fileType = fileTypeCode == null ? null : DocumentFileTypeEnum.getRc(fileTypeCode);
        if (fileType == DocumentFileTypeEnum.TXT || fileType == DocumentFileTypeEnum.MD
                || fileType == DocumentFileTypeEnum.HTML) {
            return "LIGHT_TEXT";
        }
        if (fileType == null) {
            return "UNKNOWN";
        }
        return "ALIYUN_DOCMIND";
    }

    private String parseModeDescription(Integer fileTypeCode) {
        String parseMode = parseModeCode(fileTypeCode);
        if ("LIGHT_TEXT".equals(parseMode)) {
            return "轻量文本解析";
        }
        if ("ALIYUN_DOCMIND".equals(parseMode)) {
            return "阿里云 Document Mind OCR/Layout";
        }
        return "未知解析模式";
    }

    private Long requireSourceParseTaskId(SuperAgentDocument document, SuperAgentDocumentTask indexTask) {
        Long sourceParseTaskId = indexTask == null ? null : indexTask.getSourceParseTaskId();
        SuperAgentDocumentTask sourceParseTask = sourceParseTaskId == null ? null
                : taskMapper.selectById(sourceParseTaskId);
        if (document == null || indexTask == null || !Objects.equals(indexTask.getDocumentId(), document.getId())
                || !Objects.equals(indexTask.getTaskType(), DocumentTaskTypeEnum.BUILD_INDEX.getCode())
                || sourceParseTask == null || !Objects.equals(sourceParseTask.getDocumentId(), document.getId())
                || !Objects.equals(sourceParseTask.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())
                || !Objects.equals(sourceParseTask.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())
                || !Objects.equals(sourceParseTask.getStatus(), BusinessStatus.YES.getCode())) {
            throw new IllegalStateException("索引任务缺少有效且已冻结的源解析任务 lineage");
        }
        return sourceParseTaskId;
    }

    private void persistParserTraceMetadata(Long taskId, DocumentAnalysisResult analysisResult) {
        if (taskId == null || analysisResult == null || analysisResult.getParserTraceMetadata() == null
                || analysisResult.getParserTraceMetadata().isEmpty()) {
            return;
        }
        SuperAgentDocumentTask persistedTask = taskMapper.selectById(taskId);
        if (persistedTask == null) {
            return;
        }
        try {
            Map<String, Object> extJson = readTaskExtJson(persistedTask.getExtJson());
            extJson.put("parserTraceMetadata", analysisResult.getParserTraceMetadata());
            SuperAgentDocumentTask update = new SuperAgentDocumentTask();
            update.setId(taskId);
            update.setExtJson(objectMapper.writeValueAsString(extJson));
            taskMapper.updateById(update);
        }
        catch (JsonProcessingException exception) {
            log.warn("写入解析 trace metadata 失败，taskId={}", taskId, exception);
        }
    }

    private Map<String, Object> readTaskExtJson(String extJson) throws JsonProcessingException {
        if (StrUtil.isBlank(extJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(extJson, EXT_JSON_TYPE);
        }
        catch (JsonProcessingException exception) {
            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("legacyExtJson", extJson);
            return legacy;
        }
    }

    private int embeddingBatchSize() {
        Integer configured = properties.getIndexBuild().getEmbeddingBatchSize();
        if (configured == null || configured <= 0) {
            return DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT;
        }
        return Math.min(configured, DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT);
    }

    private int batchCount(int total, int batchSize) {
        if (total <= 0) {
            return 0;
        }
        return (total + Math.max(1, batchSize) - 1) / Math.max(1, batchSize);
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private String stageLabel(Integer stage) {
        if (stage == null) {
            return "UNKNOWN";
        }
        return stage + "-" + stageName(stage);
    }

    private String stageName(Integer stage) {
        DocumentTaskStageEnum stageEnum = DocumentTaskStageEnum.getRc(stage);
        return stageEnum == null ? "UNKNOWN" : stageEnum.getMsg();
    }

    private byte[] decodeBase64(String contentBase64, String label) {
        try {
            return Base64.getDecoder().decode(contentBase64);
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalStateException(label + " Base64 解码失败", exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (Exception exception) {
            throw new IllegalStateException("计算解析产物 hash 失败", exception);
        }
    }

    private record ParentChildEntityBundle(List<SuperAgentDocumentParentBlock> parentBlocks,
            List<SuperAgentDocumentChunk> childChunks) {
    }
}
