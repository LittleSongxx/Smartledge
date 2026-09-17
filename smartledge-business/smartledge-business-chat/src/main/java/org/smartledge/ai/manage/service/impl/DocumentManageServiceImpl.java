package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.manage.data.SuperAgentDocumentParentBlock;
import org.smartledge.ai.manage.data.SuperAgentDocumentParseArtifact;
import org.smartledge.ai.manage.data.SuperAgentDocumentProfile;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.data.SuperAgentDocumentTaskLog;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeBase;
import org.smartledge.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.smartledge.ai.manage.dto.DocumentChunkQueryDto;
import org.smartledge.ai.manage.dto.DocumentChunkDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentDeleteDto;
import org.smartledge.ai.manage.dto.DocumentDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentIndexBuildDto;
import org.smartledge.ai.manage.dto.DocumentIndexBuildProgressQueryDto;
import org.smartledge.ai.manage.dto.DocumentPageQueryDto;
import org.smartledge.ai.manage.dto.DocumentParseArtifactContentQueryDto;
import org.smartledge.ai.manage.dto.DocumentParseArtifactQueryDto;
import org.smartledge.ai.manage.dto.DocumentParseRouteProgressQueryDto;
import org.smartledge.ai.manage.dto.DocumentStrategyConfirmDto;
import org.smartledge.ai.manage.dto.DocumentStrategyPlanQueryDto;
import org.smartledge.ai.manage.dto.DocumentStrategyStepItemDto;
import org.smartledge.ai.manage.dto.DocumentTaskLogQueryDto;
import org.smartledge.ai.manage.dto.DocumentUploadDto;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentStrategyPlanMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentStrategyStepMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskLogMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.smartledge.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.smartledge.ai.manage.mq.DocumentMessagePublisher;
import org.smartledge.ai.manage.mq.message.DocumentIndexBuildMessage;
import org.smartledge.ai.manage.mq.message.DocumentParseRouteMessage;
import org.smartledge.ai.manage.service.DocumentAclStore;
import org.smartledge.ai.manage.service.DocumentIndexBuildProgressCacheService;
import org.smartledge.ai.manage.service.DocumentManageService;
import org.smartledge.ai.manage.service.DocumentNavigationIndexService;
import org.smartledge.ai.manage.service.DocumentParseArtifactService;
import org.smartledge.ai.manage.service.DocumentParseRouteProgressCacheService;
import org.smartledge.ai.manage.service.DocumentStorageService;
import org.smartledge.ai.manage.service.DocumentStructureGraphProjectionService;
import org.smartledge.ai.manage.service.DocumentStructureNodeService;
import org.smartledge.ai.manage.service.DocumentStrategyService;
import org.smartledge.ai.manage.service.DocumentTaskLogService;
import org.smartledge.ai.manage.service.DocumentVectorGateway;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagBuildService;
import org.smartledge.ai.manage.service.KnowledgeBaseManageService;
import org.smartledge.ai.manage.service.KnowledgeRouteIndexService;
import org.smartledge.ai.knowledge.augmentation.service.RaptorBuildService;
import org.smartledge.ai.manage.service.keyword.DocumentKeywordSearchGateway;
import org.smartledge.ai.manage.support.DocumentParseArtifactAssembler;
import org.smartledge.ai.manage.support.RepairableBuildTaskPolicy;
import org.smartledge.ai.manage.support.DocumentMetadataJsonParser;
import org.smartledge.ai.manage.support.StoredObjectInfo;
import org.smartledge.ai.manage.support.StoredObjectMetadata;
import org.smartledge.ai.manage.vo.DocumentChunkItemVo;
import org.smartledge.ai.manage.vo.DocumentChunkQueryVo;
import org.smartledge.ai.manage.vo.DocumentChunkDetailVo;
import org.smartledge.ai.manage.vo.DocumentDeleteVo;
import org.smartledge.ai.manage.vo.DocumentIndexBuildProgressVo;
import org.smartledge.ai.manage.vo.DocumentIndexBuildVo;
import org.smartledge.ai.manage.vo.DocumentListItemVo;
import org.smartledge.ai.manage.vo.DocumentParentBlockItemVo;
import org.smartledge.ai.manage.vo.DocumentPageQueryVo;
import org.smartledge.ai.manage.vo.DocumentParseArtifactContentVo;
import org.smartledge.ai.manage.vo.DocumentParseArtifactDownloadVo;
import org.smartledge.ai.manage.vo.DocumentParseArtifactItemVo;
import org.smartledge.ai.manage.vo.DocumentParseArtifactListVo;
import org.smartledge.ai.manage.vo.DocumentParseRouteProgressVo;
import org.smartledge.ai.manage.vo.DocumentStrategyConfirmVo;
import org.smartledge.ai.manage.vo.DocumentStrategyPipelineVo;
import org.smartledge.ai.manage.vo.DocumentStrategyPlanQueryVo;
import org.smartledge.ai.manage.vo.DocumentStrategyPlanVo;
import org.smartledge.ai.manage.vo.DocumentStrategyStepVo;
import org.smartledge.ai.manage.vo.DocumentTaskLogQueryVo;
import org.smartledge.ai.manage.vo.DocumentTaskLogVo;
import org.smartledge.ai.manage.vo.DocumentUploadVo;
import org.smartledge.enums.BaseCode;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentChunkSourceTypeEnum;
import org.smartledge.enums.DocumentFileTypeEnum;
import org.smartledge.enums.DocumentIndexStatusEnum;
import org.smartledge.enums.DocumentLogLevelEnum;
import org.smartledge.enums.DocumentManageCode;
import org.smartledge.enums.DocumentOperatorTypeEnum;
import org.smartledge.enums.DocumentParseStatusEnum;
import org.smartledge.enums.DocumentPlanSourceEnum;
import org.smartledge.enums.DocumentPlanStatusEnum;
import org.smartledge.enums.DocumentStorageTypeEnum;
import org.smartledge.enums.DocumentStrategyExecuteStatusEnum;
import org.smartledge.enums.DocumentStrategyPipelineTypeEnum;
import org.smartledge.enums.DocumentStrategyRoleEnum;
import org.smartledge.enums.DocumentStrategySourceTypeEnum;
import org.smartledge.enums.DocumentStrategyStatusEnum;
import org.smartledge.enums.DocumentStrategyTypeEnum;
import org.smartledge.enums.DocumentTaskEventTypeEnum;
import org.smartledge.enums.DocumentTaskStageEnum;
import org.smartledge.enums.DocumentTaskStatusEnum;
import org.smartledge.enums.DocumentTaskTypeEnum;
import org.smartledge.enums.DocumentTriggerSourceEnum;
import org.smartledge.enums.DocumentVectorStatusEnum;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.smartledge.ai.manage.support.ChunkingContract;
import org.smartledge.ai.manage.support.ChunkingProfileException;
import org.smartledge.ai.manage.support.DocumentChunkingProfiles;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@Slf4j
@AllArgsConstructor
@Service
public class DocumentManageServiceImpl implements DocumentManageService {

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentStrategyPlanMapper planMapper;

    private final SuperAgentDocumentStrategyStepMapper stepMapper;

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentTaskLogMapper taskLogMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;

    private final SuperAgentDocumentProfileMapper documentProfileMapper;

    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;

    private final DocumentStorageService storageService;

    private final DocumentParseArtifactService parseArtifactService;

    private final DocumentStructureNodeService structureNodeService;

    private final DocumentStrategyService strategyService;

    private final DocumentChunkingProfiles chunkingProfiles;

    private final DocumentTaskLogService taskLogService;

    private final DocumentIndexBuildProgressCacheService progressCacheService;

    private final DocumentParseRouteProgressCacheService parseRouteProgressCacheService;

    private final DocumentVectorGateway vectorGateway;

    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;

    private final ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider;

    private final ObjectProvider<DocumentStructureGraphProjectionService> graphProjectionServiceProvider;

    private final ObjectProvider<KnowledgeRouteIndexService> knowledgeRouteIndexServiceProvider;

    private final GraphRagBuildService graphRagBuildService;

    private final RaptorBuildService raptorBuildService;

    private final KnowledgeBaseManageService knowledgeBaseManageService;
    private final DocumentAclStore documentAclStore;

    private final DocumentMessagePublisher messagePublisher;

    private final TransactionTemplate transactionTemplate;

    private final UidGenerator uidGenerator;

    private final DocumentManageProperties properties;

    private final ObjectMapper objectMapper;

    @Override
    public DocumentUploadVo upload(MultipartFile file, DocumentUploadDto dto) {

        if (file == null || file.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.EMPTY_FILE_CONTENT.getCode(),
                DocumentManageCode.EMPTY_FILE_CONTENT.getMsg());
        }

        String originalFileName = file.getOriginalFilename();
        if (StrUtil.isBlank(originalFileName)) {
            throw new SuperAgentFrameException(DocumentManageCode.UNSUPPORTED_FILE_TYPE.getCode(),
                "上传文件缺少原始文件名，无法识别文件类型。");
        }

        DocumentFileTypeEnum fileType = DocumentFileTypeEnum.fromFileName(originalFileName);
        if (fileType == null) {
            throw new SuperAgentFrameException(DocumentManageCode.UNSUPPORTED_FILE_TYPE.getCode(),
                DocumentManageCode.UNSUPPORTED_FILE_TYPE.getMsg());
        }

        byte[] fileBytes = getFileBytes(file);
        Long documentId = uidGenerator.getUid();
        Long knowledgeBaseId = parseRequiredLong(dto.getKnowledgeBaseId(), "knowledgeBaseId");
        SuperAgentKnowledgeBase knowledgeBase = knowledgeBaseManageService.requireEnabled(knowledgeBaseId);
        String documentMetadataJson = null;
        if (StrUtil.isNotBlank(dto.getMetadataJson())) {
            try {
                new DocumentMetadataJsonParser(objectMapper).parse(dto.getMetadataJson());
            }
            catch (IllegalArgumentException exception) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "文档 metadata JSON 非法。", exception);
            }
            documentMetadataJson = dto.getMetadataJson().trim();
        }

        StoredObjectInfo storedObjectInfo = storageService.uploadOriginalFile(
                documentId, originalFileName, fileBytes, file.getContentType());

        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(documentId);
        document.setDocumentName(StrUtil.isNotBlank(dto.getDocumentName()) ? dto.getDocumentName() : originalFileName);
        document.setOriginalFileName(originalFileName);
        document.setFileType(fileType.getCode());
        document.setMimeType(file.getContentType());
        document.setFileSize((long) fileBytes.length);
        document.setStorageType(DocumentStorageTypeEnum.MINIO.getCode());
        document.setBucketName(storedObjectInfo.getBucketName());
        document.setObjectName(storedObjectInfo.getObjectName());
        document.setObjectUrl(storedObjectInfo.getObjectUrl());
        document.setParseStatus(DocumentParseStatusEnum.PARSING.getCode());
        document.setStrategyStatus(DocumentStrategyStatusEnum.WAIT_RECOMMEND.getCode());
        document.setIndexStatus(DocumentIndexStatusEnum.WAIT_BUILD.getCode());
        document.setCharCount(0);
        document.setTokenCount(0);

        document.setKnowledgeBaseId(knowledgeBase.getId());
        document.setKnowledgeBaseName(knowledgeBase.getBaseName());
        document.setMetadataJson(documentMetadataJson);
        document.setStatus(BusinessStatus.YES.getCode());

        Long taskId = uidGenerator.getUid();
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(taskId);
        task.setDocumentId(documentId);
        task.setTaskType(DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        task.setTaskStatus(DocumentTaskStatusEnum.NEW.getCode());
        task.setCurrentStage(DocumentTaskStageEnum.FILE_UPLOAD.getCode());
        Long operatorId = parseOptionalLong(dto.getOperatorId());
        task.setTriggerSource(resolveTriggerSource(operatorId));
        task.setRetryCount(0);
        task.setStatus(BusinessStatus.YES.getCode());

        DocumentUploadVo uploadVo = transactionTemplate.execute(status -> {
            documentMapper.insert(document);
            // 入库即落 ACL：上传者对自己的文档拿 MANAGE，否则新建文档会立刻对自己不可见。
            org.smartledge.database.tenant.RequestIdentity uploader = requireDocumentOperator();
            documentAclStore.grant(documentId, uploader,
                org.smartledge.ai.manage.data.SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER, uploader.userId(),
                org.smartledge.ai.manage.data.SuperAgentDocumentAcl.PERMISSION_MANAGE, uploader.userId());
            taskMapper.insert(task);

            SuperAgentDocumentTaskLog uploadLog = taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.FILE_UPLOAD.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                resolveOperatorType(operatorId),
                operatorId,
                "文件上传完成，已进入解析与策略推荐队列。",
                Map.of("originalFileName", originalFileName, "fileSize", fileBytes.length));
            parseRouteProgressCacheService.update(document, task, uploadLog);

            return new DocumentUploadVo(documentId, taskId, document.getDocumentName(),
                document.getParseStatus(), document.getStrategyStatus(), document.getIndexStatus());
        });

        messagePublisher.publishParseRoute(new DocumentParseRouteMessage(documentId, taskId));

        return uploadVo;
    }

    /**
     * 文档写路径的权限判定（fail closed）。
     *
     * <p>权限编码（如 {@code document:delete}）回答"能不能做这类操作"，文档 ACL 回答"能不能对**这份**文档做"。
     * 两者都满足才放行。ACL 读取不可用或没有授权行一律视为无权限：文档级授权必须显式存在。</p>
     *
     * @param needManage {@code true} 要求 MANAGE（删除），{@code false} 要求 WRITE 及以上（策略确认、构建索引）
     */
    private void requireDocumentAccess(Long documentId, boolean needManage) {
        org.smartledge.database.tenant.RequestIdentity identity = requireDocumentOperator();
        if (documentId == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "文档id不能为空。");
        }
        Long resolvedDocumentId = documentId;
        java.util.Set<Long> allowed = needManage
            ? documentAclStore.manageableDocumentIds(java.util.List.of(resolvedDocumentId), identity)
            : documentAclStore.writableDocumentIds(java.util.List.of(resolvedDocumentId), identity);
        if (!allowed.contains(resolvedDocumentId)) {
            throw new org.smartledge.ai.auth.support.AuthFailureException(403,
                needManage ? "当前账号没有删除该文档的权限" : "当前账号没有修改该文档的权限");
        }
    }

    private org.smartledge.database.tenant.RequestIdentity requireDocumentOperator() {
        org.smartledge.database.tenant.RequestIdentity identity =
            org.smartledge.database.tenant.TenantContext.getIdentity();
        if (identity == null) {
            throw new org.smartledge.ai.auth.support.AuthFailureException(401, "请先登录");
        }
        return identity;
    }

    @Override
    public DocumentPageQueryVo queryDocumentPage(DocumentPageQueryDto dto) {

        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0 ? 10 : dto.getPageSize();
        String keyword = StrUtil.isNotBlank(dto.getKeyword()) ? dto.getKeyword().trim() : null;

        Page<SuperAgentDocument> page = new Page<>(pageNo, pageSize);
        LambdaQueryWrapper<SuperAgentDocument> wrapper = new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocument::getEditTime, SuperAgentDocument::getId);

        if (keyword != null) {
            wrapper.and(query -> query.like(SuperAgentDocument::getDocumentName, keyword)
                .or()
                .like(SuperAgentDocument::getOriginalFileName, keyword));
        }

        IPage<SuperAgentDocument> resultPage = documentMapper.selectPage(page, wrapper);
        List<SuperAgentDocument> documentList = resultPage.getRecords();
        Map<Long, SuperAgentDocumentTask> latestTaskMap = getLatestTaskMap(documentList);

        List<DocumentListItemVo> records = documentList.stream()
            .map(document -> toDocumentListItemVo(document, latestTaskMap.get(document.getId())))
            .toList();

        return new DocumentPageQueryVo(pageNo, pageSize, resultPage.getTotal(), records);
    }

    @Override
    public DocumentListItemVo queryDocumentDetail(DocumentDetailQueryDto dto) {
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        SuperAgentDocumentTask latestTask = getLatestTask(document.getId());
        return toDocumentListItemVo(document, latestTask);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentDeleteVo deleteDocument(DocumentDeleteDto dto) {
        Long documentId = parseRequiredLong(dto.getDocumentId(), "文档id");
        requireDocumentAccess(documentId, true);
        SuperAgentDocument document = getDocumentOrThrow(documentId);

        long activeTaskCount = taskMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.NEW.getCode(), DocumentTaskStatusEnum.RUNNING.getCode()));
        if (activeTaskCount > 0) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(),
                "当前文档存在进行中的任务，请等待任务结束后再删除。");
        }

        List<String> objectNames = new ArrayList<>();
        objectNames.add(document.getObjectName());
        objectNames.add(document.getParseTextPath());
        objectNames.addAll(parseArtifactService.listObjectNamesByDocumentId(documentId));
        storageService.deleteObjects(objectNames);
        vectorGateway.deleteByDocumentId(documentId);
        graphRagBuildService.deleteByDocumentId(documentId);
        raptorBuildService.deleteByDocumentId(documentId);

        DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
        if (keywordSearchGateway != null) {
            log.info("删除文档关键词索引: documentId={}", documentId);
            keywordSearchGateway.deleteByDocumentId(documentId);
        }
        DocumentNavigationIndexService navigationIndexService = navigationIndexServiceProvider.getIfAvailable();
        if (navigationIndexService != null) {
            log.info("删除文档导航索引: documentId={}", documentId);
            navigationIndexService.deleteByDocumentId(documentId);
        }
        KnowledgeRouteIndexService knowledgeRouteIndexService = knowledgeRouteIndexServiceProvider.getIfAvailable();
        if (knowledgeRouteIndexService != null) {
            log.info("删除知识路由索引中的文档快照: documentId={}", documentId);
            knowledgeRouteIndexService.deleteDocumentRoute(documentId);
        }
        DocumentStructureGraphProjectionService graphProjectionService = graphProjectionServiceProvider.getIfAvailable();
        if (graphProjectionService != null && graphProjectionService.enabled()) {
            log.info("删除文档结构图投影: documentId={}", documentId);
            graphProjectionService.deleteByDocumentId(documentId);
        }

        documentProfileMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getDocumentId, documentId));
        topicDocumentRelationMapper.delete(new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getDocumentId, documentId));
        parentBlockMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
            .eq(SuperAgentDocumentParentBlock::getDocumentId, documentId));
        chunkMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getDocumentId, documentId));
        structureNodeService.deleteByDocumentId(documentId);
        parseArtifactService.deleteByDocumentId(documentId);
        taskLogMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTaskLog>()
            .eq(SuperAgentDocumentTaskLog::getDocumentId, documentId));
        stepMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getDocumentId, documentId));
        taskMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId));
        planMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentStrategyPlan>()
            .eq(SuperAgentDocumentStrategyPlan::getDocumentId, documentId));
        documentMapper.deleteById(documentId);

        return new DocumentDeleteVo(documentId, document.getDocumentName());
    }

    @Override
    public DocumentStrategyPlanQueryVo queryStrategyPlan(DocumentStrategyPlanQueryDto dto) {

        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        DocumentStrategyPlanVo planVo = null;
        boolean planReady = false;

        if (document.getCurrentPlanId() != null) {
            SuperAgentDocumentStrategyPlan plan = planMapper.selectById(document.getCurrentPlanId());
            if (plan != null && Objects.equals(plan.getStatus(), BusinessStatus.YES.getCode())) {
                List<SuperAgentDocumentStrategyStep> stepList = listStepByPlanId(plan.getId());
                planVo = toPlanVo(plan, stepList);
                planReady = true;
            }
        }

        return new DocumentStrategyPlanQueryVo(
            document.getId(),
            document.getDocumentName(),
            document.getParseStatus(),
            enumMsg(DocumentParseStatusEnum.getRc(document.getParseStatus())),
            document.getStrategyStatus(),
            enumMsg(DocumentStrategyStatusEnum.getRc(document.getStrategyStatus())),
            document.getIndexStatus(),
            enumMsg(DocumentIndexStatusEnum.getRc(document.getIndexStatus())),
            document.getParseErrorMsg(),
            planReady,
            planVo
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentStrategyConfirmVo confirmStrategy(DocumentStrategyConfirmDto dto) {

        requireDocumentAccess(dto.getDocumentId(), false);
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        if (!Objects.equals(document.getParseStatus(), DocumentParseStatusEnum.PARSE_SUCCESS.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(), "当前文档还未完成解析，不能确认策略。");
        }

        if (!Objects.equals(document.getCurrentPlanId(), dto.getBasePlanId())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(), "当前文档的基础方案不存在或已切换。");
        }

        SuperAgentDocumentStrategyPlan basePlan = planMapper.selectById(dto.getBasePlanId());
        if (basePlan == null || !Objects.equals(basePlan.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(),
                DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getMsg());
        }

        List<SuperAgentDocumentStrategyStep> baseStepList = listStepByPlanId(basePlan.getId());
        ChunkingContract baseContract = ChunkingContract.read(basePlan.getChunkingContractJson(), objectMapper);
        ChunkingContract confirmedContract = null;
        if (baseContract != null) {
            if (!Objects.equals(basePlan.getDocumentId(), document.getId())) {
                throw new ChunkingProfileException("PROFILE_PLAN_INVALID", null);
            }
            baseContract.requireSource(document.getLastParseTaskId());
            if (dto.getChunkingProfile() != null || baseContract.qa()) {
                if (dto.getChunkingProfile() == null || !Objects.equals(dto.getBasePlanVersion(), basePlan.getPlanVersion())) {
                    throw new ChunkingProfileException("PROFILE_CONFIRMATION_REQUIRED", null);
                }
                baseContract.requireSource(dto.getSourceParseTaskId());
            }
            confirmedContract = baseContract.confirm(dto.getChunkingProfile() == null ? ChunkingContract.GENERIC : dto.getChunkingProfile());
            if (confirmedContract.qa()) {
                chunkingProfiles.load(document.getId(), confirmedContract,
                    parseArtifactService.listBlocks(document.getId(), confirmedContract.sourceParseTaskId()));
            }
        } else if (dto.getChunkingProfile() != null && !ChunkingContract.GENERIC.equals(dto.getChunkingProfile())) {
            throw new ChunkingProfileException("PROFILE_REPARSE_REQUIRED", null);
        }
        List<Integer> requestParentTypeList = dto.getParentSteps().stream()
            .sorted(Comparator.comparing(item -> item.getStepNo() == null ? Integer.MAX_VALUE : item.getStepNo()))
            .map(DocumentStrategyStepItemDto::getStrategyType)
            .filter(Objects::nonNull)
            .toList();
        List<Integer> requestChildTypeList = dto.getChildSteps().stream()
            .sorted(Comparator.comparing(item -> item.getStepNo() == null ? Integer.MAX_VALUE : item.getStepNo()))
            .map(DocumentStrategyStepItemDto::getStrategyType)
            .filter(Objects::nonNull)
            .toList();

        List<SuperAgentDocumentStrategyStep> normalizedStepList = strategyService.normalizeSteps(
            basePlan, baseStepList, requestParentTypeList, requestChildTypeList, dto.getDocumentId());

        List<Integer> normalizedParentTypeList = extractPipelineTypes(normalizedStepList, DocumentStrategyPipelineTypeEnum.PARENT);
        List<Integer> normalizedChildTypeList = extractPipelineTypes(normalizedStepList, DocumentStrategyPipelineTypeEnum.CHILD);
        if (confirmedContract != null) { confirmedContract.requireStrategies(normalizedStepList); }

        if (normalizedParentTypeList.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_STEP_EMPTY.getCode(), "父块流水线不能为空。");
        }
        if (normalizedChildTypeList.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_STEP_EMPTY.getCode(), "子块流水线不能为空。");
        }

        if (normalizedStepList.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_STEP_EMPTY.getCode(),
                DocumentManageCode.STRATEGY_STEP_EMPTY.getMsg());
        }

        List<Integer> baseParentTypeList = extractPipelineTypes(baseStepList, DocumentStrategyPipelineTypeEnum.PARENT);
        List<Integer> baseChildTypeList = extractPipelineTypes(baseStepList, DocumentStrategyPipelineTypeEnum.CHILD);
        List<Integer> requestDistinctParentTypeList = new LinkedHashSet<>(requestParentTypeList).stream().toList();
        List<Integer> requestDistinctChildTypeList = new LinkedHashSet<>(requestChildTypeList).stream().toList();

        boolean normalized = !requestDistinctParentTypeList.equals(normalizedParentTypeList)
            || !requestDistinctChildTypeList.equals(normalizedChildTypeList);

        boolean changed = !baseParentTypeList.equals(normalizedParentTypeList)
            || !baseChildTypeList.equals(normalizedChildTypeList)
            || (confirmedContract != null && !Objects.equals(baseContract.profile(), confirmedContract.profile()))
            || (baseContract != null && baseContract.confirmed());

        Long targetPlanId;
        Integer targetPlanVersion;
        List<SuperAgentDocumentStrategyStep> targetStepList;

        if (!changed) {

            basePlan.setPlanStatus(DocumentPlanStatusEnum.CONFIRMED.getCode());
            basePlan.setPlanSource(basePlan.getPlanSource() == null ? DocumentPlanSourceEnum.SYSTEM_RECOMMEND.getCode() : basePlan.getPlanSource());
            basePlan.setAdjustNote(dto.getAdjustNote());
            basePlan.setConfirmUserId(dto.getOperatorId());
            basePlan.setConfirmTime(new Date());
            if (confirmedContract != null) { basePlan.setChunkingContractJson(confirmedContract.json(objectMapper)); }
            planMapper.updateById(basePlan);
            targetPlanId = basePlan.getId();
            targetPlanVersion = basePlan.getPlanVersion();
            targetStepList = baseStepList;
        } else {

            basePlan.setPlanStatus(DocumentPlanStatusEnum.DISCARDED.getCode());
            planMapper.updateById(basePlan);

            Long newPlanId = uidGenerator.getUid();
            Integer newPlanVersion = getNextPlanVersion(document.getId());
            SuperAgentDocumentStrategyPlan newPlan = new SuperAgentDocumentStrategyPlan();
            newPlan.setId(newPlanId);
            newPlan.setDocumentId(document.getId());
            newPlan.setPlanVersion(newPlanVersion);

            newPlan.setPlanSource(DocumentPlanSourceEnum.USER_ADJUST.getCode());
            newPlan.setPlanStatus(DocumentPlanStatusEnum.CONFIRMED.getCode());
            newPlan.setStrategyCount(normalizedStepList.size());
            newPlan.setStrategySnapshot(buildStrategySnapshot(normalizedStepList));
            if (confirmedContract != null) { newPlan.setChunkingContractJson(confirmedContract.json(objectMapper)); }
            newPlan.setRecommendReason(basePlan.getRecommendReason());
            newPlan.setAdjustNote(dto.getAdjustNote());
            newPlan.setConfirmUserId(dto.getOperatorId());
            newPlan.setConfirmTime(new Date());
            newPlan.setStatus(BusinessStatus.YES.getCode());
            planMapper.insert(newPlan);

            for (SuperAgentDocumentStrategyStep step : normalizedStepList) {
                step.setId(uidGenerator.getUid());
                step.setPlanId(newPlanId);
                step.setStatus(BusinessStatus.YES.getCode());
                stepMapper.insert(step);
            }

            targetPlanId = newPlanId;
            targetPlanVersion = newPlanVersion;
            targetStepList = normalizedStepList;
        }

        document.setCurrentPlanId(targetPlanId);
        document.setStrategyStatus(DocumentStrategyStatusEnum.CONFIRMED.getCode());
        documentMapper.updateById(document);

        SuperAgentDocumentTask latestParseTask = getLatestTask(document.getId(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        if (latestParseTask != null) {

            latestParseTask.setCurrentStage(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode());
            taskMapper.updateById(latestParseTask);

            if (changed) {

                taskLogService.saveLog(latestParseTask.getId(), document.getId(),
                    DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode(),
                    DocumentTaskEventTypeEnum.USER_ADJUST.getCode(),
                    DocumentLogLevelEnum.INFO.getCode(),
                    resolveOperatorType(parseOptionalLong(dto.getOperatorId())),
                    parseOptionalLong(dto.getOperatorId()),
                    "用户调整了系统推荐策略。",
                    detail("parentStrategyTypes", normalizedParentTypeList,
                        "childStrategyTypes", normalizedChildTypeList,
                        "adjustNote", dto.getAdjustNote()));
            }

            taskLogService.saveLog(latestParseTask.getId(), document.getId(),
                DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode(),
                DocumentTaskEventTypeEnum.USER_CONFIRM.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                    resolveOperatorType(parseOptionalLong(dto.getOperatorId())),
                    parseOptionalLong(dto.getOperatorId()),
                    "用户已确认最终策略方案。",
                Map.of("planId", targetPlanId,
                    "parentStrategyTypes", normalizedParentTypeList,
                    "childStrategyTypes", normalizedChildTypeList));
        }

        return new DocumentStrategyConfirmVo(
            document.getId(),
            targetPlanId,
            targetPlanVersion,
            document.getStrategyStatus(),
            enumMsg(DocumentStrategyStatusEnum.getRc(document.getStrategyStatus())),
            normalized,
            toPipelineVo(DocumentStrategyPipelineTypeEnum.PARENT, targetStepList),
            toPipelineVo(DocumentStrategyPipelineTypeEnum.CHILD, targetStepList),
            confirmedContract
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentIndexBuildVo buildIndex(DocumentIndexBuildDto dto) {

        requireDocumentAccess(dto.getDocumentId(), false);
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        if (!Objects.equals(document.getParseStatus(), DocumentParseStatusEnum.PARSE_SUCCESS.getCode())
            || !Objects.equals(document.getStrategyStatus(), DocumentStrategyStatusEnum.CONFIRMED.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(), "当前文档尚未完成“解析成功 + 策略确认”，不能构建索引。");
        }

        if (!Objects.equals(document.getCurrentPlanId(), dto.getPlanId())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(), "当前文档的生效方案与请求方案不一致。");
        }

        List<SuperAgentDocumentTask> activeBuildTasks = taskMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, dto.getDocumentId())
            .eq(SuperAgentDocumentTask::getTaskType, DocumentTaskTypeEnum.BUILD_INDEX.getCode())
            .in(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.NEW.getCode(), DocumentTaskStatusEnum.RUNNING.getCode())
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode()));
        long blockingTaskCount = 0L;
        SuperAgentDocumentTask repairableTask = null;
        for (SuperAgentDocumentTask activeTask : activeBuildTasks) {
            if (RepairableBuildTaskPolicy.isRepairRequired(activeTask.getExtJson())) {
                repairableTask = activeTask;
            }
            else {
                blockingTaskCount++;
            }
        }
        if (blockingTaskCount > 0) {
            throw new SuperAgentFrameException(DocumentManageCode.INDEX_TASK_RUNNING.getCode(),
                DocumentManageCode.INDEX_TASK_RUNNING.getMsg());
        }
        if (repairableTask != null) {
            // S21-O：可修复任务不锁死文档。原来的行为是"等对账任务 2 小时后判失联"，
            // 期间文档既不可检索也不可重建。现在由新的构建请求取代它：
            // 构建链路会从已提交的图谱检查点继续（repair），而不是重跑抽取。
            supersedeRepairableBuildTask(repairableTask, document);
        }

        SuperAgentDocumentStrategyPlan plan = planMapper.selectById(dto.getPlanId());
        if (plan == null || !Objects.equals(plan.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(),
                DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getMsg());
        }

        Long sourceParseTaskId = document.getLastParseTaskId();
        ChunkingContract buildContract = ChunkingContract.read(plan.getChunkingContractJson(), objectMapper);
        if (buildContract != null) {
            buildContract.requireConfirmed();
            buildContract.requireSource(sourceParseTaskId);
            if (!Objects.equals(plan.getDocumentId(), document.getId())) {
                throw new ChunkingProfileException("PROFILE_PLAN_INVALID", null);
            }
            buildContract.requireStrategies(listStepByPlanId(plan.getId()));
        }
        SuperAgentDocumentTask sourceParseTask = sourceParseTaskId == null
            ? null
            : taskMapper.selectById(sourceParseTaskId);
        if (sourceParseTask == null
            || !Objects.equals(sourceParseTask.getDocumentId(), document.getId())
            || !Objects.equals(sourceParseTask.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())
            || !Objects.equals(sourceParseTask.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())
            || !Objects.equals(sourceParseTask.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(),
                "当前文档缺少可冻结的成功解析任务，不能构建索引。");
        }

        Long taskId = uidGenerator.getUid();
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(taskId);
        task.setDocumentId(document.getId());
        task.setPlanId(dto.getPlanId());
        task.setSourceParseTaskId(sourceParseTaskId);
        task.setTaskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode());
        task.setTaskStatus(DocumentTaskStatusEnum.NEW.getCode());
        task.setCurrentStage(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode());
        Long operatorId = parseOptionalLong(dto.getOperatorId());
        task.setTriggerSource(resolveTriggerSource(operatorId));
        task.setStrategySnapshot(plan.getStrategySnapshot());
        task.setRetryCount(0);
        task.setStatus(BusinessStatus.YES.getCode());
        if (buildContract != null) { buildContract.freezeTask(task, objectMapper); }
        taskMapper.insert(task);

        document.setIndexStatus(DocumentIndexStatusEnum.BUILDING.getCode());
        documentMapper.updateById(document);

        SuperAgentDocumentTaskLog taskLog = taskLogService.saveLog(taskId, document.getId(),
            DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
            DocumentTaskEventTypeEnum.START.getCode(),
            DocumentLogLevelEnum.INFO.getCode(),
            resolveOperatorType(operatorId),
            operatorId,
            "索引构建任务已创建，等待异步执行。",
            Map.of(
                "planId", dto.getPlanId(),
                "strategySnapshot", plan.getStrategySnapshot(),
                "sourceParseTaskId", sourceParseTaskId
            ));

        sendIndexBuildAfterCommit(document, task, taskLog, dto.getPlanId(), operatorId);

        return new DocumentIndexBuildVo(
            document.getId(),
            taskId,
            task.getTaskType(),
            enumMsg(DocumentTaskTypeEnum.getRc(task.getTaskType())),
            task.getTaskStatus(),
            enumMsg(DocumentTaskStatusEnum.getRc(task.getTaskStatus())),
            document.getIndexStatus(),
            enumMsg(DocumentIndexStatusEnum.getRc(document.getIndexStatus()))
        );
    }

    @Override
    public DocumentTaskLogQueryVo queryTaskLogs(DocumentTaskLogQueryDto dto) {

        SuperAgentDocumentTask task = taskMapper.selectById(dto.getTaskId());
        if (task == null || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "任务不存在。");
        }

        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0 ? 20 : dto.getPageSize();
        Page<SuperAgentDocumentTaskLog> page = new Page<>(pageNo, pageSize);

        IPage<SuperAgentDocumentTaskLog> resultPage = taskLogMapper.selectPage(page,
            new LambdaQueryWrapper<SuperAgentDocumentTaskLog>()
                .eq(SuperAgentDocumentTaskLog::getTaskId, dto.getTaskId())
                .eq(SuperAgentDocumentTaskLog::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentTaskLog::getCreateTime, SuperAgentDocumentTaskLog::getId));

        List<DocumentTaskLogVo> logVoList = resultPage.getRecords().stream()
            .map(this::toTaskLogVo)
            .toList();

        return new DocumentTaskLogQueryVo(
            task.getId(),
            task.getDocumentId(),
            task.getTaskType(),
            enumMsg(DocumentTaskTypeEnum.getRc(task.getTaskType())),
            task.getTaskStatus(),
            enumMsg(DocumentTaskStatusEnum.getRc(task.getTaskStatus())),
            task.getCurrentStage(),
            enumMsg(DocumentTaskStageEnum.getRc(task.getCurrentStage())),
            task.getStartTime(),
            task.getFinishTime(),
            task.getCostMillis(),
            task.getErrorCode(),
            task.getErrorMsg(),
            resultPage.getTotal(),
            logVoList
        );
    }

    private void sendIndexBuildAfterCommit(SuperAgentDocument document,
                                           SuperAgentDocumentTask task,
                                           SuperAgentDocumentTaskLog taskLog,
                                           Long planId,
                                           Long operatorId) {
        Runnable sender = () -> {
            Long documentId = document == null ? null : document.getId();
            Long taskId = task == null ? null : task.getId();
            progressCacheService.update(document, task, taskLog);
            try {
                messagePublisher.publishIndexBuild(new DocumentIndexBuildMessage(documentId, taskId, planId));
            }
            catch (Exception exception) {
                markIndexBuildSubmitFailed(documentId, taskId, operatorId, exception);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sender.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                sender.run();
            }
        });
    }

    private void markIndexBuildSubmitFailed(Long documentId, Long taskId, Long operatorId, Exception exception) {
        log.error("索引构建消息投递失败，已标记任务失败，documentId={}, taskId={}", documentId, taskId, exception);
        try {
            SuperAgentDocumentTask task = taskMapper.selectById(taskId);
            if (task != null && Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())) {
                Date finishTime = new Date();
                task.setTaskStatus(DocumentTaskStatusEnum.FAILED.getCode());
                task.setCurrentStage(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode());
                task.setFinishTime(finishTime);
                Date startTime = task.getStartTime() == null ? task.getCreateTime() : task.getStartTime();
                task.setCostMillis(startTime == null ? 0L : Math.max(0L, finishTime.getTime() - startTime.getTime()));
                task.setErrorCode("INDEX_BUILD_SUBMIT_FAILED");
                task.setErrorMsg(StrUtil.maxLength(exception.getMessage(), 1000));
                taskMapper.updateById(task);
            }
            SuperAgentDocument document = documentMapper.selectById(documentId);
            if (document != null && Objects.equals(document.getStatus(), BusinessStatus.YES.getCode())) {
                document.setIndexStatus(DocumentIndexStatusEnum.BUILD_FAILED.getCode());
                documentMapper.updateById(document);
            }
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                DocumentTaskEventTypeEnum.FAILED.getCode(),
                DocumentLogLevelEnum.ERROR.getCode(),
                resolveOperatorType(operatorId),
                operatorId,
                "索引构建后台任务提交失败，未进入切块执行。",
                Map.of("error", StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getName())));
        }
        catch (Exception markException) {
            log.error("标记索引构建提交失败状态时发生异常，documentId={}, taskId={}", documentId, taskId, markException);
        }
    }

    @Override
    public DocumentIndexBuildProgressVo queryIndexBuildProgress(DocumentIndexBuildProgressQueryDto dto) {
        DocumentIndexBuildProgressVo cachedByTaskId = cachedBuildProgress(dto);
        if (cachedByTaskId != null) {
            return cachedByTaskId;
        }

        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        SuperAgentDocumentTask task = dto.getTaskId() == null
            ? getLatestTask(document.getId(), DocumentTaskTypeEnum.BUILD_INDEX.getCode())
            : taskMapper.selectById(dto.getTaskId());
        if (task != null && (!Objects.equals(task.getDocumentId(), document.getId())
            || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())
            || !Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.BUILD_INDEX.getCode()))) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "索引构建任务不存在。");
        }
        DocumentIndexBuildProgressVo cachedProgress = cachedBuildProgress(document, task, dto);
        if (cachedProgress != null) {
            return cachedProgress;
        }

        List<DocumentTaskLogVo> logs = task == null ? List.of() : listProgressLogs(task.getId(), dto.getSinceLogId(), resolveProgressLogLimit(dto.getLogLimit()));
        Long totalLogCount = task == null ? 0L : countTaskLogs(task.getId());
        Long latestLogId = logs.stream()
            .map(DocumentTaskLogVo::getId)
            .filter(Objects::nonNull)
            .max(Long::compareTo)
            .orElse(dto.getSinceLogId());
        boolean building = task != null && (
            Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.NEW.getCode())
                || Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.RUNNING.getCode())
                || Objects.equals(document.getIndexStatus(), DocumentIndexStatusEnum.BUILDING.getCode())
        );
        Long elapsedMillis = resolveElapsedMillis(task);

        return new DocumentIndexBuildProgressVo(
            document.getId(),
            document.getIndexStatus(),
            enumMsg(DocumentIndexStatusEnum.getRc(document.getIndexStatus())),
            task == null ? null : task.getId(),
            task == null ? null : task.getTaskType(),
            task == null ? "" : enumMsg(DocumentTaskTypeEnum.getRc(task.getTaskType())),
            task == null ? null : task.getTaskStatus(),
            task == null ? "" : enumMsg(DocumentTaskStatusEnum.getRc(task.getTaskStatus())),
            task == null ? null : task.getCurrentStage(),
            task == null ? "" : enumMsg(DocumentTaskStageEnum.getRc(task.getCurrentStage())),
            task == null ? null : task.getStartTime(),
            task == null ? null : task.getFinishTime(),
            task == null ? null : task.getCostMillis(),
            elapsedMillis,
            task == null ? null : task.getErrorCode(),
            task == null ? null : task.getErrorMsg(),
            task == null ? null : task.getExtJson(),
            building,
            latestLogId,
            totalLogCount,
            logs
        );
    }

    @Override
    public DocumentParseRouteProgressVo queryParseRouteProgress(DocumentParseRouteProgressQueryDto dto) {
        DocumentParseRouteProgressVo cachedByTaskId = cachedParseRouteProgress(dto);
        if (cachedByTaskId != null) {
            return cachedByTaskId;
        }

        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        SuperAgentDocumentTask task = dto.getTaskId() == null
            ? getLatestTask(document.getId(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())
            : taskMapper.selectById(dto.getTaskId());
        if (task != null && (!Objects.equals(task.getDocumentId(), document.getId())
            || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())
            || !Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode()))) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "解析路由任务不存在。");
        }
        DocumentParseRouteProgressVo cachedProgress = cachedParseRouteProgress(document, task, dto);
        if (cachedProgress != null) {
            return cachedProgress;
        }

        int logLimit = resolveProgressLogLimit(dto.getLogLimit());
        List<DocumentTaskLogVo> logs = task == null ? List.of() : listProgressLogs(task.getId(), dto.getSinceLogId(), logLimit);
        Long totalLogCount = task == null ? 0L : countTaskLogs(task.getId());
        SuperAgentDocumentStrategyPlan plan = currentPlan(document);
        List<SuperAgentDocumentStrategyStep> steps = plan == null ? List.of() : listStepByPlanId(plan.getId());

        return DocumentParseRouteProgressAssembler.build(
            document,
            task,
            plan,
            steps,
            logs,
            totalLogCount,
            objectMapper
        );
    }

    @Override
    public DocumentParseArtifactListVo queryParseArtifacts(DocumentParseArtifactQueryDto dto) {
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        Long effectiveTaskId = resolveParseArtifactTaskId(document, dto.getTaskId());
        if (effectiveTaskId == null) {
            return new DocumentParseArtifactListVo(document.getId(), null, List.of());
        }
        validateParseRouteTask(document, effectiveTaskId);

        List<DocumentParseArtifactItemVo> artifacts = parseArtifactService.listArtifacts(document.getId(), effectiveTaskId)
            .stream()
            .map(artifact -> DocumentParseArtifactAssembler.toItem(artifact, getArtifactMetadata(artifact)))
            .filter(Objects::nonNull)
            .toList();
        return new DocumentParseArtifactListVo(document.getId(), effectiveTaskId, artifacts);
    }

    @Override
    public DocumentParseArtifactContentVo queryParseArtifactContent(DocumentParseArtifactContentQueryDto dto) {
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        SuperAgentDocumentParseArtifact artifact = getParseArtifactOrThrow(document, dto.getTaskId(), dto.getArtifactId());
        DocumentParseArtifactItemVo item = DocumentParseArtifactAssembler.toItem(artifact, getArtifactMetadata(artifact));
        if (item == null || !Boolean.TRUE.equals(item.getViewable())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(),
                "当前解析产物不支持文本预览，请下载后查看。");
        }
        return DocumentParseArtifactAssembler.toContent(artifact, storageService.downloadObject(artifact.getObjectName()));
    }

    @Override
    public DocumentParseArtifactDownloadVo downloadParseArtifact(DocumentParseArtifactContentQueryDto dto) {
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        SuperAgentDocumentParseArtifact artifact = getParseArtifactOrThrow(document, dto.getTaskId(), dto.getArtifactId());
        return DocumentParseArtifactAssembler.toDownload(artifact, storageService.downloadObject(artifact.getObjectName()));
    }

    @Override
    public DocumentChunkQueryVo queryDocumentChunks(DocumentChunkQueryDto dto) {
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0 ? 20 : dto.getPageSize();

        Long effectiveTaskId = resolveChunkTaskId(document, dto.getTaskId());
        if (effectiveTaskId == null) {
            return new DocumentChunkQueryVo(document.getId(), null, document.getCurrentPlanId(), pageNo, pageSize, 0L, List.of());
        }

        SuperAgentDocumentTask task = taskMapper.selectById(effectiveTaskId);
        if (task == null
            || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())
            || !Objects.equals(task.getDocumentId(), document.getId())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "切块任务不存在。");
        }

        Page<SuperAgentDocumentChunk> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentDocumentChunk> resultPage = chunkMapper.selectPage(page,
            new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getDocumentId, document.getId())
                .eq(SuperAgentDocumentChunk::getTaskId, effectiveTaskId)
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId));

        Map<Long, SuperAgentDocumentParentBlock> parentBlockMap = listParentBlockMap(
            resultPage.getRecords().stream()
                .map(SuperAgentDocumentChunk::getParentBlockId)
                .filter(Objects::nonNull)
                .toList()
        );

        List<DocumentChunkItemVo> records = resultPage.getRecords().stream()
            .map(chunk -> toDocumentChunkItemVo(chunk, parentBlockMap.get(chunk.getParentBlockId())))
            .toList();

        return new DocumentChunkQueryVo(
            document.getId(),
            effectiveTaskId,
            task.getPlanId(),
            pageNo,
            pageSize,
            resultPage.getTotal(),
            records
        );
    }

    @Override
    public DocumentChunkDetailVo queryDocumentChunkDetail(DocumentChunkDetailQueryDto dto) {
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        Long effectiveTaskId = resolveChunkTaskId(document, dto.getTaskId());
        if (effectiveTaskId == null) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "当前文档还没有可查看的 chunk 详情。");
        }

        SuperAgentDocumentTask task = taskMapper.selectById(effectiveTaskId);
        if (task == null
            || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())
            || !Objects.equals(task.getDocumentId(), document.getId())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "切块任务不存在。");
        }

        SuperAgentDocumentChunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getId, dto.getChunkId())
            .eq(SuperAgentDocumentChunk::getDocumentId, document.getId())
            .eq(SuperAgentDocumentChunk::getTaskId, effectiveTaskId)
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
            .last("limit 1"));
        if (chunk == null) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "chunk 详情不存在。");
        }

        SuperAgentDocumentParentBlock parentBlock = chunk.getParentBlockId() == null
            ? null
            : parentBlockMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
                .eq(SuperAgentDocumentParentBlock::getId, chunk.getParentBlockId())
                .eq(SuperAgentDocumentParentBlock::getDocumentId, document.getId())
                .eq(SuperAgentDocumentParentBlock::getTaskId, effectiveTaskId)
                .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode())
                .last("limit 1"));

        List<SuperAgentDocumentChunk> siblingChunkList = chunk.getParentBlockId() == null
            ? List.of(chunk)
            : chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getDocumentId, document.getId())
                .eq(SuperAgentDocumentChunk::getTaskId, effectiveTaskId)
                .eq(SuperAgentDocumentChunk::getParentBlockId, chunk.getParentBlockId())
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId));

        return new DocumentChunkDetailVo(
            document.getId(),
            effectiveTaskId,
            task.getPlanId(),
            toDocumentChunkItemVo(chunk, parentBlock),
            toDocumentParentBlockItemVo(parentBlock),
            siblingChunkList.stream()
                .map(item -> toDocumentChunkItemVo(item, parentBlock))
                .toList()
        );
    }

    /**
     * 取代一个"可修复"的构建任务：标失败、释放文档，并留下可审计的任务日志。
     *
     * <p>释放语义与对账任务的失联释放保持一致（构建中 → 构建失败），因此随后的正常构建流程会把
     * 文档重新置为构建中，不会出现两套状态解释。</p>
     */
    private void supersedeRepairableBuildTask(SuperAgentDocumentTask repairableTask, SuperAgentDocument document) {
        repairableTask.setTaskStatus(DocumentTaskStatusEnum.FAILED.getCode());
        repairableTask.setErrorMsg("已由新的构建请求取代：原任务停留在可修复状态，本次构建将从已提交的图谱检查点继续。");
        repairableTask.setFinishTime(org.smartledge.util.DateUtils.now());
        taskMapper.updateById(repairableTask);
        if (Objects.equals(document.getIndexStatus(), DocumentIndexStatusEnum.BUILDING.getCode())) {
            documentMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocument>()
                .eq(SuperAgentDocument::getId, document.getId())
                .set(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_FAILED.getCode()));
            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_FAILED.getCode());
        }
        taskLogService.saveLog(repairableTask.getId(), document.getId(),
            DocumentTaskStageEnum.GRAPH_TYPED_INDEX.getCode(),
            DocumentTaskEventTypeEnum.FAILED.getCode(),
            DocumentLogLevelEnum.WARN.getCode(),
            org.smartledge.enums.DocumentOperatorTypeEnum.USER.getCode(),
            org.smartledge.database.tenant.TenantContext.getIdentity() == null
                ? null : org.smartledge.database.tenant.TenantContext.getIdentity().userId(),
            "原构建任务处于可修复状态，已被新的构建请求取代；本次构建从已提交的图谱检查点继续。",
            Map.of("supersededTaskId", String.valueOf(repairableTask.getId())));
        log.info("已取代可修复的构建任务，documentId={}, supersededTaskId={}", document.getId(), repairableTask.getId());
    }

    private SuperAgentDocument getDocumentOrThrow(Long documentId) {

        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null || !Objects.equals(document.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(),
                DocumentManageCode.DOCUMENT_NOT_FOUND.getMsg());
        }
        return document;
    }

    private List<SuperAgentDocumentStrategyStep> listStepByPlanId(Long planId) {
        List<SuperAgentDocumentStrategyStep> stepList = stepMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
            .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode()));
        return stepList.stream()
            .sorted(Comparator
                .comparingInt((SuperAgentDocumentStrategyStep step) -> pipelineOrder(step.getPipelineType()))
                .thenComparing(SuperAgentDocumentStrategyStep::getStepNo)
                .thenComparing(SuperAgentDocumentStrategyStep::getId))
            .toList();
    }

    private Integer getNextPlanVersion(Long documentId) {

        SuperAgentDocumentStrategyPlan latestPlan = planMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentStrategyPlan>()
            .eq(SuperAgentDocumentStrategyPlan::getDocumentId, documentId)
            .eq(SuperAgentDocumentStrategyPlan::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentStrategyPlan::getPlanVersion)
            .last("limit 1"));
        return latestPlan == null ? 1 : latestPlan.getPlanVersion() + 1;
    }

    private SuperAgentDocumentTask getLatestTask(Long documentId, Integer taskType) {

        return taskMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId)
            .eq(SuperAgentDocumentTask::getTaskType, taskType)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentTask::getId)
            .last("limit 1"));
    }

    private SuperAgentDocumentTask getLatestTask(Long documentId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentTask::getId)
            .last("limit 1"));
    }

    private List<DocumentTaskLogVo> listProgressLogs(Long taskId, Long sinceLogId, int logLimit) {
        LambdaQueryWrapper<SuperAgentDocumentTaskLog> wrapper = new LambdaQueryWrapper<SuperAgentDocumentTaskLog>()
            .eq(SuperAgentDocumentTaskLog::getTaskId, taskId)
            .eq(SuperAgentDocumentTaskLog::getStatus, BusinessStatus.YES.getCode());
        if (sinceLogId != null && sinceLogId > 0) {
            wrapper.gt(SuperAgentDocumentTaskLog::getId, sinceLogId)
                .orderByAsc(SuperAgentDocumentTaskLog::getCreateTime, SuperAgentDocumentTaskLog::getId)
                .last("limit " + logLimit);
            return taskLogMapper.selectList(wrapper).stream()
                .map(this::toTaskLogVo)
                .toList();
        }
        wrapper.orderByDesc(SuperAgentDocumentTaskLog::getCreateTime, SuperAgentDocumentTaskLog::getId)
            .last("limit " + logLimit);
        List<DocumentTaskLogVo> logs = new ArrayList<>(taskLogMapper.selectList(wrapper).stream()
            .map(this::toTaskLogVo)
            .toList());
        logs.sort(Comparator
            .comparing(DocumentTaskLogVo::getCreateTime, Comparator.nullsLast(Date::compareTo))
            .thenComparing(DocumentTaskLogVo::getId, Comparator.nullsLast(Long::compareTo)));
        return logs;
    }

    private DocumentIndexBuildProgressVo cachedBuildProgress(DocumentIndexBuildProgressQueryDto dto) {
        if (dto == null || dto.getTaskId() == null) {
            return null;
        }
        DocumentIndexBuildProgressVo cached = progressCacheService.get(dto.getTaskId());
        if (cached == null
            || !Objects.equals(cached.getDocumentId(), dto.getDocumentId())
            || !Objects.equals(cached.getTaskType(), DocumentTaskTypeEnum.BUILD_INDEX.getCode())) {
            return null;
        }
        return cachedBuildProgress(cached, dto);
    }

    private DocumentIndexBuildProgressVo cachedBuildProgress(SuperAgentDocument document,
                                                             SuperAgentDocumentTask task,
                                                             DocumentIndexBuildProgressQueryDto dto) {
        if (document == null || task == null || task.getId() == null) {
            return null;
        }
        DocumentIndexBuildProgressVo cached = progressCacheService.get(task.getId());
        if (cached == null || !Objects.equals(cached.getDocumentId(), document.getId())) {
            return null;
        }
        return cachedBuildProgress(cached, dto);
    }

    private DocumentIndexBuildProgressVo cachedBuildProgress(DocumentIndexBuildProgressVo cached,
                                                             DocumentIndexBuildProgressQueryDto dto) {
        List<DocumentTaskLogVo> filteredLogs = filterCachedProgressLogs(
            cached.getLogs(), dto.getSinceLogId(), resolveProgressLogLimit(dto.getLogLimit())
        );
        Long latestLogId = cached.getLatestLogId();
        if (latestLogId == null) {
            latestLogId = filteredLogs.stream()
                .map(DocumentTaskLogVo::getId)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(dto.getSinceLogId());
        }
        return new DocumentIndexBuildProgressVo(
            cached.getDocumentId(),
            cached.getIndexStatus(),
            cached.getIndexStatusName(),
            cached.getTaskId(),
            cached.getTaskType(),
            cached.getTaskTypeName(),
            cached.getTaskStatus(),
            cached.getTaskStatusName(),
            cached.getCurrentStage(),
            cached.getCurrentStageName(),
            cached.getStartTime(),
            cached.getFinishTime(),
            cached.getCostMillis(),
            resolveCachedElapsedMillis(cached),
            cached.getErrorCode(),
            cached.getErrorMsg(),
            cached.getExtJson(),
            cached.getBuilding(),
            latestLogId,
            cached.getTotalLogCount(),
            filteredLogs
        );
    }

    private Long resolveCachedElapsedMillis(DocumentIndexBuildProgressVo cached) {
        if (cached == null) {
            return null;
        }
        if (cached.getCostMillis() != null && cached.getCostMillis() > 0) {
            return cached.getCostMillis();
        }
        if (cached.getStartTime() == null) {
            return cached.getElapsedMillis();
        }
        Date endTime = cached.getFinishTime() == null ? new Date() : cached.getFinishTime();
        return Math.max(0L, endTime.getTime() - cached.getStartTime().getTime());
    }

    private List<DocumentTaskLogVo> filterCachedProgressLogs(List<DocumentTaskLogVo> logs, Long sinceLogId, int logLimit) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }
        List<DocumentTaskLogVo> filtered = logs.stream()
            .filter(log -> log != null && log.getId() != null)
            .filter(log -> sinceLogId == null || sinceLogId <= 0 || log.getId() > sinceLogId)
            .sorted(Comparator
                .comparing(DocumentTaskLogVo::getCreateTime, Comparator.nullsLast(Date::compareTo))
                .thenComparing(DocumentTaskLogVo::getId, Comparator.nullsLast(Long::compareTo)))
            .toList();
        if (filtered.size() <= logLimit) {
            return filtered;
        }
        return filtered.subList(filtered.size() - logLimit, filtered.size());
    }

    private DocumentParseRouteProgressVo cachedParseRouteProgress(DocumentParseRouteProgressQueryDto dto) {
        if (dto == null || dto.getTaskId() == null) {
            return null;
        }
        DocumentParseRouteProgressVo cached = parseRouteProgressCacheService.get(dto.getTaskId());
        if (cached == null
            || !Objects.equals(cached.getDocumentId(), dto.getDocumentId())
            || !Objects.equals(cached.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())) {
            return null;
        }
        return cachedParseRouteProgress(cached, dto);
    }

    private DocumentParseRouteProgressVo cachedParseRouteProgress(SuperAgentDocument document,
                                                                  SuperAgentDocumentTask task,
                                                                  DocumentParseRouteProgressQueryDto dto) {
        if (document == null || task == null || task.getId() == null) {
            return null;
        }
        DocumentParseRouteProgressVo cached = parseRouteProgressCacheService.get(task.getId());
        if (cached == null
            || !Objects.equals(cached.getDocumentId(), document.getId())
            || !Objects.equals(cached.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())) {
            return null;
        }
        return cachedParseRouteProgress(cached, dto);
    }

    private DocumentParseRouteProgressVo cachedParseRouteProgress(DocumentParseRouteProgressVo cached,
                                                                  DocumentParseRouteProgressQueryDto dto) {
        List<DocumentTaskLogVo> filteredLogs = DocumentParseRouteProgressAssembler.filterLogs(
            cached.getLogs(), dto.getSinceLogId(), resolveProgressLogLimit(dto.getLogLimit())
        );
        Long latestLogId = cached.getLatestLogId();
        if (latestLogId == null) {
            latestLogId = DocumentParseRouteProgressAssembler.latestLogId(filteredLogs);
            if (latestLogId == null) {
                latestLogId = dto.getSinceLogId();
            }
        }
        DocumentParseRouteProgressVo result = new DocumentParseRouteProgressVo();
        org.springframework.beans.BeanUtils.copyProperties(cached, result);
        result.setLogs(filteredLogs);
        result.setLatestLogId(latestLogId);
        result.setElapsedMillis(DocumentParseRouteProgressAssembler.cachedElapsedMillis(cached));
        return result;
    }

    private Long countTaskLogs(Long taskId) {
        if (taskId == null) {
            return 0L;
        }
        return taskLogMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentTaskLog>()
            .eq(SuperAgentDocumentTaskLog::getTaskId, taskId)
            .eq(SuperAgentDocumentTaskLog::getStatus, BusinessStatus.YES.getCode()));
    }

    private int resolveProgressLogLimit(Integer requestedLimit) {
        int configured = properties.getIndexBuild().getProgressLogLimit() == null
            ? 60 : properties.getIndexBuild().getProgressLogLimit();
        int limit = requestedLimit == null || requestedLimit <= 0 ? configured : requestedLimit;
        return Math.max(1, Math.min(limit, 200));
    }

    private SuperAgentDocumentStrategyPlan currentPlan(SuperAgentDocument document) {
        if (document == null || document.getCurrentPlanId() == null) {
            return null;
        }
        SuperAgentDocumentStrategyPlan plan = planMapper.selectById(document.getCurrentPlanId());
        if (plan == null || !Objects.equals(plan.getStatus(), BusinessStatus.YES.getCode())) {
            return null;
        }
        return plan;
    }

    private Long resolveElapsedMillis(SuperAgentDocumentTask task) {
        if (task == null) {
            return null;
        }
        if (task.getCostMillis() != null && task.getCostMillis() > 0) {
            return task.getCostMillis();
        }
        if (task.getStartTime() == null) {
            return 0L;
        }
        Date endTime = task.getFinishTime() == null ? new Date() : task.getFinishTime();
        return Math.max(0L, endTime.getTime() - task.getStartTime().getTime());
    }

    private Map<Long, SuperAgentDocumentTask> getLatestTaskMap(List<SuperAgentDocument> documentList) {
        if (documentList == null || documentList.isEmpty()) {
            return Map.of();
        }

        Set<Long> documentIdSet = documentList.stream()
            .map(SuperAgentDocument::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (documentIdSet.isEmpty()) {
            return Map.of();
        }

        List<SuperAgentDocumentTask> taskList = taskMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .in(SuperAgentDocumentTask::getDocumentId, documentIdSet)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentTask::getId));

        Map<Long, SuperAgentDocumentTask> latestTaskMap = new LinkedHashMap<>();
        for (SuperAgentDocumentTask task : taskList) {
            latestTaskMap.putIfAbsent(task.getDocumentId(), task);
        }
        return latestTaskMap;
    }

    private Long resolveChunkTaskId(SuperAgentDocument document, Long requestedTaskId) {
        if (requestedTaskId != null) {
            return requestedTaskId;
        }
        if (document.getLastIndexTaskId() != null) {
            return document.getLastIndexTaskId();
        }
        SuperAgentDocumentTask latestBuildTask = getLatestTask(document.getId(), DocumentTaskTypeEnum.BUILD_INDEX.getCode());
        return latestBuildTask == null ? null : latestBuildTask.getId();
    }

    private Long resolveParseArtifactTaskId(SuperAgentDocument document, Long requestedTaskId) {
        if (requestedTaskId != null) {
            return requestedTaskId;
        }
        if (document.getLastParseTaskId() != null) {
            return document.getLastParseTaskId();
        }
        SuperAgentDocumentTask latestParseTask = getLatestTask(document.getId(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        return latestParseTask == null ? null : latestParseTask.getId();
    }

    private void validateParseRouteTask(SuperAgentDocument document, Long taskId) {
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (task == null
            || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())
            || !Objects.equals(task.getDocumentId(), document.getId())
            || !Objects.equals(task.getTaskType(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "解析任务不存在。");
        }
    }

    private SuperAgentDocumentParseArtifact getParseArtifactOrThrow(SuperAgentDocument document, Long requestedTaskId, Long artifactId) {
        Long effectiveTaskId = resolveParseArtifactTaskId(document, requestedTaskId);
        if (effectiveTaskId == null) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "当前文档还没有可查看的解析产物。");
        }
        validateParseRouteTask(document, effectiveTaskId);
        return parseArtifactService.listArtifacts(document.getId(), effectiveTaskId).stream()
            .filter(artifact -> Objects.equals(artifact.getId(), artifactId))
            .findFirst()
            .orElseThrow(() -> new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "解析产物不存在。"));
    }

    private StoredObjectMetadata getArtifactMetadata(SuperAgentDocumentParseArtifact artifact) {
        if (artifact == null || StrUtil.isBlank(artifact.getObjectName())) {
            return null;
        }
        return storageService.getObjectMetadata(artifact.getObjectName());
    }

    private DocumentListItemVo toDocumentListItemVo(SuperAgentDocument document, SuperAgentDocumentTask latestTask) {
        return new DocumentListItemVo(
            document.getId(),
            document.getDocumentName(),
            document.getOriginalFileName(),
            document.getFileType(),
            enumMsg(DocumentFileTypeEnum.getRc(document.getFileType())),
            document.getFileSize(),
            document.getCharCount(),
            document.getTokenCount(),
            document.getParseStatus(),
            enumMsg(DocumentParseStatusEnum.getRc(document.getParseStatus())),
            document.getStrategyStatus(),
            enumMsg(DocumentStrategyStatusEnum.getRc(document.getStrategyStatus())),
            document.getIndexStatus(),
            enumMsg(DocumentIndexStatusEnum.getRc(document.getIndexStatus())),
            document.getParseErrorMsg(),
            document.getKnowledgeBaseId(),
            document.getKnowledgeBaseName(),
            document.getMetadataJson(),
            document.getCurrentPlanId(),
            document.getLastIndexTaskId(),
            latestTask == null ? null : latestTask.getId(),
            latestTask == null ? null : latestTask.getTaskType(),
            latestTask == null ? "" : enumMsg(DocumentTaskTypeEnum.getRc(latestTask.getTaskType())),
            latestTask == null ? null : latestTask.getTaskStatus(),
            latestTask == null ? "" : enumMsg(DocumentTaskStatusEnum.getRc(latestTask.getTaskStatus())),
            document.getCreateTime(),
            document.getEditTime()
        );
    }

    private DocumentChunkItemVo toDocumentChunkItemVo(SuperAgentDocumentChunk chunk,
                                                     SuperAgentDocumentParentBlock parentBlock) {
        return new DocumentChunkItemVo(
            chunk.getId(),
            chunk.getParentBlockId(),
            parentBlock == null ? null : parentBlock.getParentNo(),
            parentBlock == null ? null : parentBlock.getChildCount(),
            parentBlock == null ? null : parentBlock.getStartChunkNo(),
            parentBlock == null ? null : parentBlock.getEndChunkNo(),
            chunk.getChunkNo(),
            chunk.getSectionPath(),
            chunk.getSourceType(),
            enumMsg(DocumentChunkSourceTypeEnum.getRc(chunk.getSourceType())),
            chunk.getCharCount(),
            chunk.getTokenCount(),
            chunk.getVectorStatus(),
            enumMsg(DocumentVectorStatusEnum.getRc(chunk.getVectorStatus())),
            chunk.getPageNo(),
            chunk.getPageRange(),
            chunk.getBboxJson(),
            chunk.getSourceBlockIds(),
            chunk.getContentWithWeight(),
            chunk.getChunkType(),
            chunk.getTitle(),
            chunk.getKeywords(),
            chunk.getQuestions(),
            chunk.getChunkText(),
            chunk.getSourceProvenanceJson()
        );
    }

    private DocumentParentBlockItemVo toDocumentParentBlockItemVo(SuperAgentDocumentParentBlock parentBlock) {
        if (parentBlock == null) {
            return null;
        }
        return new DocumentParentBlockItemVo(
            parentBlock.getId(),
            parentBlock.getParentNo(),
            parentBlock.getSectionPath(),
            parentBlock.getSourceType(),
            enumMsg(DocumentChunkSourceTypeEnum.getRc(parentBlock.getSourceType())),
            parentBlock.getCharCount(),
            parentBlock.getTokenCount(),
            parentBlock.getChildCount(),
            parentBlock.getStartChunkNo(),
            parentBlock.getEndChunkNo(),
            parentBlock.getPageRange(),
            parentBlock.getSourceBlockIds(),
            parentBlock.getParentText(),
            parentBlock.getSourceProvenanceJson()
        );
    }

    private Map<Long, SuperAgentDocumentParentBlock> listParentBlockMap(List<Long> parentBlockIds) {
        if (parentBlockIds == null || parentBlockIds.isEmpty()) {
            return Map.of();
        }
        return parentBlockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
                .in(SuperAgentDocumentParentBlock::getId, parentBlockIds)
                .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.toMap(
                SuperAgentDocumentParentBlock::getId,
                item -> item,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private DocumentStrategyPlanVo toPlanVo(SuperAgentDocumentStrategyPlan plan, List<SuperAgentDocumentStrategyStep> stepList) {
        return new DocumentStrategyPlanVo(
            plan.getId(),
            plan.getPlanVersion(),
            plan.getPlanSource(),
            enumMsg(DocumentPlanSourceEnum.getRc(plan.getPlanSource())),
            plan.getPlanStatus(),
            enumMsg(DocumentPlanStatusEnum.getRc(plan.getPlanStatus())),
            plan.getStrategySnapshot(),
            plan.getRecommendReason(),
            toPipelineVo(DocumentStrategyPipelineTypeEnum.PARENT, stepList),
            toPipelineVo(DocumentStrategyPipelineTypeEnum.CHILD, stepList),
            ChunkingContract.read(plan.getChunkingContractJson(), objectMapper)
        );
    }

    private List<DocumentStrategyStepVo> toStepVoList(List<SuperAgentDocumentStrategyStep> stepList) {

        return stepList.stream()
            .sorted(Comparator
                .comparingInt((SuperAgentDocumentStrategyStep step) -> pipelineOrder(step.getPipelineType()))
                .thenComparing(SuperAgentDocumentStrategyStep::getStepNo)
                .thenComparing(SuperAgentDocumentStrategyStep::getId))
            .map(step -> new DocumentStrategyStepVo(
                step.getStepNo(),
                step.getPipelineType(),
                enumMsg(DocumentStrategyPipelineTypeEnum.getRc(step.getPipelineType())),
                step.getStrategyType(),
                enumMsg(DocumentStrategyTypeEnum.getRc(step.getStrategyType())),
                step.getStrategyRole(),
                enumMsg(DocumentStrategyRoleEnum.getRc(step.getStrategyRole())),
                step.getSourceType(),
                enumMsg(DocumentStrategySourceTypeEnum.getRc(step.getSourceType())),
                step.getExecuteStatus(),
                enumMsg(DocumentStrategyExecuteStatusEnum.getRc(step.getExecuteStatus())),
                step.getRecommendReason()
            ))
            .toList();
    }

    private DocumentStrategyPipelineVo toPipelineVo(DocumentStrategyPipelineTypeEnum pipelineType,
                                                    List<SuperAgentDocumentStrategyStep> stepList) {
        List<SuperAgentDocumentStrategyStep> pipelineSteps = stepList.stream()
            .filter(step -> pipelineType.getCode().equalsIgnoreCase(
                StrUtil.blankToDefault(step.getPipelineType(), DocumentStrategyPipelineTypeEnum.CHILD.getCode())
            ))
            .sorted(Comparator.comparingInt(SuperAgentDocumentStrategyStep::getStepNo))
            .toList();
        return new DocumentStrategyPipelineVo(
            pipelineType.getCode(),
            pipelineType.getMsg(),
            pipelineSteps.stream().map(step -> String.valueOf(step.getStrategyType())).collect(Collectors.joining(",")),
            toStepVoList(pipelineSteps)
        );
    }

    private List<Integer> extractPipelineTypes(List<SuperAgentDocumentStrategyStep> stepList,
                                               DocumentStrategyPipelineTypeEnum pipelineType) {
        return stepList.stream()
            .filter(step -> pipelineType.getCode().equalsIgnoreCase(
                StrUtil.blankToDefault(step.getPipelineType(), DocumentStrategyPipelineTypeEnum.CHILD.getCode())
            ))
            .sorted(Comparator.comparingInt(SuperAgentDocumentStrategyStep::getStepNo))
            .map(SuperAgentDocumentStrategyStep::getStrategyType)
            .toList();
    }

    private String buildStrategySnapshot(List<SuperAgentDocumentStrategyStep> stepList) {
        return "PARENT:" + toPipelineVo(DocumentStrategyPipelineTypeEnum.PARENT, stepList).getStrategySnapshot()
            + ";CHILD:" + toPipelineVo(DocumentStrategyPipelineTypeEnum.CHILD, stepList).getStrategySnapshot();
    }

    private int pipelineOrder(String pipelineType) {
        return DocumentStrategyPipelineTypeEnum.PARENT.getCode().equalsIgnoreCase(
            StrUtil.blankToDefault(pipelineType, "")
        ) ? 0 : 1;
    }

    private DocumentTaskLogVo toTaskLogVo(SuperAgentDocumentTaskLog logRecord) {
        return new DocumentTaskLogVo(
            logRecord.getId(),
            logRecord.getStageType(),
            enumMsg(DocumentTaskStageEnum.getRc(logRecord.getStageType())),
            logRecord.getEventType(),
            enumMsg(DocumentTaskEventTypeEnum.getRc(logRecord.getEventType())),
            logRecord.getLogLevel(),
            enumMsg(DocumentLogLevelEnum.getRc(logRecord.getLogLevel())),
            logRecord.getContent(),
            logRecord.getDetailJson(),
            logRecord.getCreateTime()
        );
    }

    private Integer resolveOperatorType(Long operatorId) {

        return operatorId == null ? DocumentOperatorTypeEnum.SYSTEM.getCode() : DocumentOperatorTypeEnum.USER.getCode();
    }

    private Integer resolveTriggerSource(Long operatorId) {

        return operatorId == null ? DocumentTriggerSourceEnum.SYSTEM.getCode() : DocumentTriggerSourceEnum.USER.getCode();
    }

    private Long parseOptionalLong(String rawValue) {
        if (StrUtil.isBlank(rawValue)) {
            return null;
        }
        try {
            Long value = Long.valueOf(rawValue.trim());
            return value > 0 ? value : null;
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private Long parseOptionalLong(Long rawValue) {
        return rawValue == null || rawValue <= 0 ? null : rawValue;
    }

    private Long parseRequiredLong(String rawValue, String fieldName) {
        if (StrUtil.isBlank(rawValue)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "不能为空。");
        }

        try {

            Long value = Long.valueOf(rawValue.trim());
            if (value <= 0) {
                throw new NumberFormatException("id must be positive");
            }
            return value;
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "格式不正确。");
        }
    }

    private String enumMsg(Object enumObject) {
        if (enumObject == null) {
            return "";
        }
        if (enumObject instanceof DocumentParseStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentFileTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategyStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentIndexStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentPlanSourceEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentPlanStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategyTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategyRoleEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategySourceTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategyExecuteStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskStageEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskEventTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentLogLevelEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentChunkSourceTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentVectorStatusEnum value) {
            return value.getMsg();
        }
        return "";
    }

    private byte[] getFileBytes(MultipartFile file) {
        try {

            return file.getBytes();
        }
        catch (IOException exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "读取上传文件内容失败: " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> detailMap = new LinkedHashMap<>();

        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            detailMap.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return detailMap;
    }
}
