package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentProfile;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeScopeNode;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeTopicNode;
import org.smartledge.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.smartledge.ai.manage.dto.DocumentProfileBatchRegenerateDto;
import org.smartledge.ai.manage.dto.DocumentProfileDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentProfileRegenerateDto;
import org.smartledge.ai.manage.dto.KnowledgeRouteTraceQueryDto;
import org.smartledge.ai.manage.dto.KnowledgeScopeDeleteDto;
import org.smartledge.ai.manage.dto.KnowledgeScopeQueryDto;
import org.smartledge.ai.manage.dto.KnowledgeScopeSaveDto;
import org.smartledge.ai.manage.dto.KnowledgeTopicDeleteDto;
import org.smartledge.ai.manage.dto.KnowledgeTopicQueryDto;
import org.smartledge.ai.manage.dto.KnowledgeTopicSaveDto;
import org.smartledge.ai.manage.dto.TopicDocumentRelationListQueryDto;
import org.smartledge.ai.manage.dto.TopicDocumentRelationRemoveDto;
import org.smartledge.ai.manage.dto.TopicDocumentRelationSaveDto;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeScopeNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeRouteTraceMapper;
import org.smartledge.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.smartledge.ai.manage.service.DocumentAclStore;
import org.smartledge.ai.manage.service.DocumentProfileService;
import org.smartledge.ai.manage.service.KnowledgeBaseManageService;
import org.smartledge.ai.manage.service.KnowledgeManageService;
import org.smartledge.ai.manage.vo.DocumentProfileVo;
import org.smartledge.ai.manage.vo.KnowledgeRouteTraceItemVo;
import org.smartledge.ai.manage.vo.KnowledgeRouteTracePageVo;
import org.smartledge.ai.manage.vo.KnowledgeScopeItemVo;
import org.smartledge.ai.manage.vo.KnowledgeTopicItemVo;
import org.smartledge.ai.manage.vo.TopicDocumentRelationItemVo;
import org.smartledge.enums.BaseCode;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @description: 服务实现层
 * @author: Song
 **/
@AllArgsConstructor
@Service
public class KnowledgeManageServiceImpl implements KnowledgeManageService {

    private final SuperAgentKnowledgeScopeNodeMapper scopeNodeMapper;
    private final SuperAgentKnowledgeTopicNodeMapper topicNodeMapper;
    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;
    private final SuperAgentKnowledgeRouteTraceMapper knowledgeRouteTraceMapper;
    private final SuperAgentDocumentMapper documentMapper;
    private final DocumentProfileService documentProfileService;
    private final KnowledgeBaseManageService knowledgeBaseManageService;
    private final DocumentAclStore documentAclStore;
    private final UidGenerator uidGenerator;

    @Override
    public KnowledgeScopeItemVo saveScope(KnowledgeScopeSaveDto dto) {
        validateScope(dto);
        Long knowledgeBaseId = parseRequiredLong(dto.getKnowledgeBaseId(), "knowledgeBaseId");
        knowledgeBaseManageService.requireEnabled(knowledgeBaseId);
        Long id = parseOptionalPositiveLong(dto.getId());
        Long parentScopeId = parseOptionalPositiveLong(dto.getParentScopeId());
        if (parentScopeId != null) {
            requireScopeInBase(parentScopeId, knowledgeBaseId, "父级知识范围不存在或不属于当前知识库。");
            if (Objects.equals(id, parentScopeId)) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "父级知识范围不能是自己。");
            }
        }
        SuperAgentKnowledgeScopeNode entity = id == null ? null : requireScopeInBase(id, knowledgeBaseId, "知识范围不存在或不属于当前知识库。");
        ensureUniqueScopeName(knowledgeBaseId, safeText(dto.getScopeName()), id);
        if (entity == null) {
            entity = new SuperAgentKnowledgeScopeNode();
            entity.setId(uidGenerator.getUid());
            entity.setStatus(BusinessStatus.YES.getCode());
            entity.setKnowledgeBaseId(knowledgeBaseId);
        }
        entity.setScopeName(safeText(dto.getScopeName()));
        entity.setParentScopeId(parentScopeId);
        entity.setDescription(safeText(dto.getDescription()));
        entity.setAliases(safeText(dto.getAliases()));
        entity.setExamples(safeText(dto.getExamples()));
        entity.setSortOrder(parseInteger(dto.getSortOrder(), 0));
        if (entity.getCreateTime() == null) {
            scopeNodeMapper.insert(entity);
        }
        else {
            scopeNodeMapper.updateById(entity);
        }
        return toScopeVo(entity);
    }

    @Override
    public boolean deleteScope(KnowledgeScopeDeleteDto dto) {
        Long id = parseRequiredLong(dto == null ? null : dto.getId(), "id");
        Long knowledgeBaseId = parseRequiredLong(dto.getKnowledgeBaseId(), "knowledgeBaseId");
        requireScopeInBase(id, knowledgeBaseId, "知识范围不存在或不属于当前知识库。");
        Long childCount = scopeNodeMapper.selectCount(new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentKnowledgeScopeNode::getParentScopeId, id)
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode()));
        if (childCount != null && childCount > 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请先删除或调整子级知识范围。");
        }
        Long topicCount = topicNodeMapper.selectCount(new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentKnowledgeTopicNode::getScopeId, id)
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode()));
        if (topicCount != null && topicCount > 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请先删除或调整该范围下的知识主题。");
        }
        return scopeNodeMapper.update(null, new LambdaUpdateWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentKnowledgeScopeNode::getId, id)
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode())
            .set(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.NO.getCode())) > 0;
    }

    @Override
    public List<KnowledgeScopeItemVo> listScopes(KnowledgeScopeQueryDto dto) {
        Long knowledgeBaseId = parseOptionalPositiveLong(dto == null ? null : dto.getKnowledgeBaseId());
        LambdaQueryWrapper<SuperAgentKnowledgeScopeNode> wrapper = new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKnowledgeScopeNode::getSortOrder, SuperAgentKnowledgeScopeNode::getId);
        if (knowledgeBaseId != null) {
            wrapper.eq(SuperAgentKnowledgeScopeNode::getKnowledgeBaseId, knowledgeBaseId);
        }
        return scopeNodeMapper.selectList(wrapper)
            .stream()
            .map(this::toScopeVo)
            .toList();
    }

    @Override
    public KnowledgeTopicItemVo saveTopic(KnowledgeTopicSaveDto dto) {
        validateTopic(dto);
        Long knowledgeBaseId = parseRequiredLong(dto.getKnowledgeBaseId(), "knowledgeBaseId");
        knowledgeBaseManageService.requireEnabled(knowledgeBaseId);
        Long id = parseOptionalPositiveLong(dto.getId());
        Long scopeId = parseRequiredLong(dto.getScopeId(), "scopeId");
        requireScopeInBase(scopeId, knowledgeBaseId, "所属知识范围不存在或不属于当前知识库。");
        SuperAgentKnowledgeTopicNode entity = id == null ? null : requireTopicInBase(id, knowledgeBaseId, "知识主题不存在或不属于当前知识库。");
        ensureUniqueTopicName(knowledgeBaseId, scopeId, safeText(dto.getTopicName()), id);
        if (entity == null) {
            entity = new SuperAgentKnowledgeTopicNode();
            entity.setId(uidGenerator.getUid());
            entity.setStatus(BusinessStatus.YES.getCode());
            entity.setKnowledgeBaseId(knowledgeBaseId);
        }
        entity.setTopicName(safeText(dto.getTopicName()));
        entity.setScopeId(scopeId);
        entity.setDescription(safeText(dto.getDescription()));
        entity.setAliases(safeText(dto.getAliases()));
        entity.setExamples(safeText(dto.getExamples()));
        entity.setAnswerShape(safeText(dto.getAnswerShape()));
        entity.setExecutionPreference(safeText(dto.getExecutionPreference()));
        entity.setSortOrder(parseInteger(dto.getSortOrder(), 0));
        if (entity.getCreateTime() == null) {
            topicNodeMapper.insert(entity);
        }
        else {
            topicNodeMapper.updateById(entity);
        }
        return toTopicVo(entity);
    }

    @Override
    public boolean deleteTopic(KnowledgeTopicDeleteDto dto) {
        Long id = parseRequiredLong(dto == null ? null : dto.getId(), "id");
        Long knowledgeBaseId = parseRequiredLong(dto.getKnowledgeBaseId(), "knowledgeBaseId");
        requireTopicInBase(id, knowledgeBaseId, "知识主题不存在或不属于当前知识库。");
        Long relationCount = topicDocumentRelationMapper.selectCount(new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentTopicDocumentRelation::getTopicId, id)
            .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode()));
        if (relationCount != null && relationCount > 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请先移除该主题下的文档关联。");
        }
        return topicNodeMapper.update(null, new LambdaUpdateWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentKnowledgeTopicNode::getId, id)
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode())
            .set(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.NO.getCode())) > 0;
    }

    @Override
    public List<KnowledgeTopicItemVo> listTopics(KnowledgeTopicQueryDto dto) {
        Long scopeId = parseOptionalPositiveLong(dto == null ? null : dto.getScopeId());
        Long knowledgeBaseId = parseOptionalPositiveLong(dto == null ? null : dto.getKnowledgeBaseId());
        LambdaQueryWrapper<SuperAgentKnowledgeTopicNode> wrapper = new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKnowledgeTopicNode::getSortOrder, SuperAgentKnowledgeTopicNode::getId);
        if (knowledgeBaseId != null) {
            wrapper.eq(SuperAgentKnowledgeTopicNode::getKnowledgeBaseId, knowledgeBaseId);
        }
        if (scopeId != null) {
            wrapper.eq(SuperAgentKnowledgeTopicNode::getScopeId, scopeId);
        }
        return topicNodeMapper.selectList(wrapper).stream().map(this::toTopicVo).toList();
    }

    @Override
    public DocumentProfileVo queryProfile(DocumentProfileDetailQueryDto dto) {
        Long documentId = parseRequiredLong(dto == null ? null : dto.getDocumentId(), "documentId");
        requireDocumentRead(documentId);
        SuperAgentDocumentProfile profile = documentProfileService.getByDocumentId(documentId)
            .orElseThrow(() -> new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "文档画像不存在。"));
        return toProfileVo(profile);
    }

    @Override
    public DocumentProfileVo regenerateProfile(DocumentProfileRegenerateDto dto) {
        Long documentId = parseRequiredLong(dto == null ? null : dto.getDocumentId(), "documentId");
        requireDocumentWrite(documentId);
        return toProfileVo(documentProfileService.regenerateProfile(documentId));
    }

    @Override
    public List<DocumentProfileVo> batchRegenerateProfiles(DocumentProfileBatchRegenerateDto dto) {
        List<Long> documentIds = dto == null || dto.getDocumentIds() == null
            ? List.of()
            : dto.getDocumentIds().stream().map(value -> parseRequiredLong(value, "documentId")).toList();
        documentIds.forEach(this::requireDocumentWrite);
        return documentProfileService.batchRegenerateProfiles(documentIds).stream()
            .map(this::toProfileVo)
            .toList();
    }

    @Override
    public List<TopicDocumentRelationItemVo> listTopicDocuments(TopicDocumentRelationListQueryDto dto) {
        Long topicId = parseOptionalPositiveLong(dto == null ? null : dto.getTopicId());
        Long knowledgeBaseId = parseOptionalPositiveLong(dto == null ? null : dto.getKnowledgeBaseId());
        LambdaQueryWrapper<SuperAgentTopicDocumentRelation> wrapper = new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentTopicDocumentRelation::getRelationScore, SuperAgentTopicDocumentRelation::getId);
        if (knowledgeBaseId != null) {
            wrapper.eq(SuperAgentTopicDocumentRelation::getKnowledgeBaseId, knowledgeBaseId);
        }
        if (topicId != null) {
            wrapper.eq(SuperAgentTopicDocumentRelation::getTopicId, topicId);
        }
        return topicDocumentRelationMapper.selectList(wrapper).stream()
            .map(this::toRelationVo)
            .toList();
    }

    @Override
    public TopicDocumentRelationItemVo saveTopicDocumentRelation(TopicDocumentRelationSaveDto dto) {
        Long knowledgeBaseId = parseRequiredLong(dto.getKnowledgeBaseId(), "knowledgeBaseId");
        knowledgeBaseManageService.requireEnabled(knowledgeBaseId);
        Long topicId = parseRequiredLong(dto.getTopicId(), "topicId");
        requireTopicInBase(topicId, knowledgeBaseId, "知识主题不存在或不属于当前知识库。");
        Long documentId = parseRequiredLong(dto.getDocumentId(), "documentId");
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null || !Long.valueOf(knowledgeBaseId).equals(document.getKnowledgeBaseId())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "文档不属于当前知识库。");
        }
        SuperAgentTopicDocumentRelation relation = topicDocumentRelationMapper.selectOne(new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentTopicDocumentRelation::getTopicId, topicId)
            .eq(SuperAgentTopicDocumentRelation::getDocumentId, documentId)
            .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
        if (relation == null) {
            relation = new SuperAgentTopicDocumentRelation();
            relation.setId(uidGenerator.getUid());
            relation.setKnowledgeBaseId(knowledgeBaseId);
            relation.setTopicId(topicId);
            relation.setDocumentId(documentId);
            relation.setStatus(BusinessStatus.YES.getCode());
        }
        relation.setRelationScore(parseDecimal(dto.getRelationScore(), BigDecimal.ZERO));
        relation.setRelationSource(firstNonBlank(dto.getRelationSource(), "manual"));
        relation.setReason(safeText(dto.getReason()));
        if (relation.getCreateTime() == null) {
            topicDocumentRelationMapper.insert(relation);
        }
        else {
            topicDocumentRelationMapper.updateById(relation);
        }
        return toRelationVo(relation);
    }

    @Override
    public boolean removeTopicDocumentRelation(TopicDocumentRelationRemoveDto dto) {
        Long knowledgeBaseId = parseRequiredLong(dto.getKnowledgeBaseId(), "knowledgeBaseId");
        Long topicId = parseRequiredLong(dto.getTopicId(), "topicId");
        Long documentId = parseRequiredLong(dto.getDocumentId(), "documentId");
        return topicDocumentRelationMapper.update(null, new LambdaUpdateWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentTopicDocumentRelation::getTopicId, topicId)
            .eq(SuperAgentTopicDocumentRelation::getDocumentId, documentId)
            .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode())
            .set(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.NO.getCode())) > 0;
    }

    @Override
    public KnowledgeRouteTracePageVo queryRouteTracePage(KnowledgeRouteTraceQueryDto dto) {
        int pageNo = parseInteger(dto == null ? null : dto.getPageNo(), 1);
        int pageSize = parseInteger(dto == null ? null : dto.getPageSize(), 20);
        String conversationId = dto == null ? "" : safeText(dto.getConversationId());
        Long tenantId = org.smartledge.database.tenant.TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "缺少租户上下文，无法查询路由追踪。");
        }
        String mode = dto == null ? "" : safeText(dto.getMode());
        String routeStatus = dto == null ? "" : safeText(dto.getRouteStatus());
        LambdaQueryWrapper<org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace> wrapper =
            new LambdaQueryWrapper<org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace>()
                .eq(org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace::getTenantId, tenantId)
                .eq(StrUtil.isNotBlank(conversationId),
                    org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace::getConversationId, conversationId)
                .eq(org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace::getStatus, BusinessStatus.YES.getCode())
                .orderByDesc(org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace::getCreateTime,
                    org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace::getId);
        if (StrUtil.isNotBlank(mode)) {
            wrapper.eq(org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace::getMode, mode);
        }
        if (StrUtil.isNotBlank(routeStatus)) {
            Integer parsedStatus = parseInteger(routeStatus, -1);
            if (parsedStatus > 0) {
                wrapper.eq(org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace::getRouteStatus, parsedStatus);
            }
        }
        long total = knowledgeRouteTraceMapper.selectCount(wrapper);
        List<KnowledgeRouteTraceItemVo> records = knowledgeRouteTraceMapper.selectList(wrapper.last("LIMIT " + ((long) (pageNo - 1) * pageSize) + "," + pageSize))
            .stream()
            .map(item -> new KnowledgeRouteTraceItemVo(
                String.valueOf(item.getId()),
                safeText(item.getConversationId()),
                item.getExchangeId() == null ? "" : String.valueOf(item.getExchangeId()),
                safeText(item.getQuestion()),
                safeText(item.getRewriteQuestion()),
                safeText(item.getMode()),
                safeText(item.getTopScopesJson()),
                safeText(item.getTopTopicsJson()),
                safeText(item.getTopDocumentsJson()),
                item.getSelectedDocumentId() == null ? "" : String.valueOf(item.getSelectedDocumentId()),
                item.getHitSelectedDocument() == null ? "" : String.valueOf(item.getHitSelectedDocument()),
                item.getConfidence() == null ? "0.0000" : item.getConfidence().toPlainString(),
                item.getRouteStatus() == null ? "" : String.valueOf(item.getRouteStatus()),
                safeText(item.getErrorMsg()),
                item.getCreateTime() == null ? "" : String.valueOf(item.getCreateTime().getTime())
            ))
            .toList();
        long totalPages = total <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new KnowledgeRouteTracePageVo(
            String.valueOf(pageNo),
            String.valueOf(pageSize),
            String.valueOf(total),
            String.valueOf(totalPages),
            records
        );
    }

    private void validateScope(KnowledgeScopeSaveDto dto) {
        if (dto == null || safeText(dto.getScopeName()).isBlank()) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "scopeName 不能为空。");
        }
    }

    private void validateTopic(KnowledgeTopicSaveDto dto) {
        if (dto == null || safeText(dto.getTopicName()).isBlank() || parseOptionalPositiveLong(dto.getScopeId()) == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "topicName 和 scopeId 不能为空。");
        }
    }

    private KnowledgeScopeItemVo toScopeVo(SuperAgentKnowledgeScopeNode node) {
        return new KnowledgeScopeItemVo(
            String.valueOf(node.getId()),
            node.getKnowledgeBaseId() == null ? "" : String.valueOf(node.getKnowledgeBaseId()),
            safeText(node.getScopeName()),
            node.getParentScopeId() == null ? "" : String.valueOf(node.getParentScopeId()),
            safeText(node.getDescription()),
            safeText(node.getAliases()),
            safeText(node.getExamples()),
            String.valueOf(Optional.ofNullable(node.getSortOrder()).orElse(0))
        );
    }

    private KnowledgeTopicItemVo toTopicVo(SuperAgentKnowledgeTopicNode node) {
        return new KnowledgeTopicItemVo(
            String.valueOf(node.getId()),
            node.getKnowledgeBaseId() == null ? "" : String.valueOf(node.getKnowledgeBaseId()),
            safeText(node.getTopicName()),
            node.getScopeId() == null ? "" : String.valueOf(node.getScopeId()),
            safeText(node.getDescription()),
            safeText(node.getAliases()),
            safeText(node.getExamples()),
            safeText(node.getAnswerShape()),
            safeText(node.getExecutionPreference()),
            String.valueOf(Optional.ofNullable(node.getSortOrder()).orElse(0))
        );
    }

    private DocumentProfileVo toProfileVo(SuperAgentDocumentProfile profile) {
        return new DocumentProfileVo(
            String.valueOf(profile.getDocumentId()),
            safeText(profile.getDocumentSummary()),
            safeText(profile.getDocumentType()),
            safeText(profile.getCoreTopics()),
            safeText(profile.getExampleQuestions()),
            String.valueOf(Optional.ofNullable(profile.getGraphFriendly()).orElse(0)),
            String.valueOf(Optional.ofNullable(profile.getSupportsGraphOutline()).orElse(0)),
            String.valueOf(Optional.ofNullable(profile.getSupportsItemLookup()).orElse(0)),
            String.valueOf(Optional.ofNullable(profile.getSupportsGraphAssist()).orElse(0)),
            safeText(profile.getProfileSource()),
            String.valueOf(Optional.ofNullable(profile.getProfileStatus()).orElse(0)),
            safeText(profile.getErrorMsg())
        );
    }

    private TopicDocumentRelationItemVo toRelationVo(SuperAgentTopicDocumentRelation relation) {
        SuperAgentDocument document = documentMapper.selectById(relation.getDocumentId());
        SuperAgentKnowledgeTopicNode topic = relation.getTopicId() == null ? null : topicNodeMapper.selectById(relation.getTopicId());
        SuperAgentKnowledgeScopeNode scope = topic == null || topic.getScopeId() == null ? null : scopeNodeMapper.selectById(topic.getScopeId());
        return new TopicDocumentRelationItemVo(
            relation.getKnowledgeBaseId() == null ? "" : String.valueOf(relation.getKnowledgeBaseId()),
            relation.getTopicId() == null ? "" : String.valueOf(relation.getTopicId()),
            topic == null ? "" : safeText(topic.getTopicName()),
            scope == null ? "" : String.valueOf(scope.getId()),
            scope == null ? "" : safeText(scope.getScopeName()),
            String.valueOf(relation.getDocumentId()),
            document == null ? "" : safeText(document.getDocumentName()),
            relation.getRelationScore() == null ? "0.0000" : relation.getRelationScore().toPlainString(),
            safeText(relation.getRelationSource()),
            safeText(relation.getReason())
        );
    }

    private SuperAgentKnowledgeScopeNode requireScopeInBase(Long scopeId, Long knowledgeBaseId, String message) {
        SuperAgentKnowledgeScopeNode scope = scopeNodeMapper.selectOne(new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getId, scopeId)
            .eq(SuperAgentKnowledgeScopeNode::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
        if (scope == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), message);
        }
        return scope;
    }

    private SuperAgentKnowledgeTopicNode requireTopicInBase(Long topicId, Long knowledgeBaseId, String message) {
        SuperAgentKnowledgeTopicNode topic = topicNodeMapper.selectOne(new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getId, topicId)
            .eq(SuperAgentKnowledgeTopicNode::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
        if (topic == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), message);
        }
        return topic;
    }

    private void ensureUniqueScopeName(Long knowledgeBaseId, String scopeName, Long currentId) {
        SuperAgentKnowledgeScopeNode existing = scopeNodeMapper.selectOne(new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentKnowledgeScopeNode::getScopeName, scopeName)
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
        if (existing != null && !Objects.equals(existing.getId(), currentId)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识范围名称已存在。");
        }
    }

    private void ensureUniqueTopicName(Long knowledgeBaseId, Long scopeId, String topicName, Long currentId) {
        SuperAgentKnowledgeTopicNode existing = topicNodeMapper.selectOne(new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getKnowledgeBaseId, knowledgeBaseId)
            .eq(SuperAgentKnowledgeTopicNode::getScopeId, scopeId)
            .eq(SuperAgentKnowledgeTopicNode::getTopicName, topicName)
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
        if (existing != null && !Objects.equals(existing.getId(), currentId)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识主题名称已存在。");
        }
    }

    private Long parseRequiredLong(String rawValue, String fieldName) {
        if (StrUtil.isBlank(rawValue)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "不能为空。");
        }
        try {
            Long value = Long.valueOf(rawValue.trim());
            if (value <= 0) {
                throw new NumberFormatException("must be positive");
            }
            return value;
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "格式非法。");
        }
    }

    private Long parseOptionalPositiveLong(String rawValue) {
        if (StrUtil.isBlank(rawValue)) {
            return null;
        }
        try {
            Long value = Long.valueOf(rawValue.trim());
            return value > 0 ? value : null;
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "id 格式非法。");
        }
    }

    private Integer parseInteger(String rawValue, Integer fallback) {
        if (StrUtil.isBlank(rawValue)) {
            return fallback;
        }
        try {
            return Integer.valueOf(rawValue.trim());
        }
        catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private BigDecimal parseDecimal(String rawValue, BigDecimal fallback) {
        if (StrUtil.isBlank(rawValue)) {
            return fallback;
        }
        try {
            return new BigDecimal(rawValue.trim());
        }
        catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private String firstNonBlank(String primary, String fallback) {
        if (StrUtil.isNotBlank(primary)) {
            return primary.trim();
        }
        return StrUtil.blankToDefault(fallback, "");
    }

    private void requireDocumentRead(Long documentId) {
        org.smartledge.database.tenant.RequestIdentity identity = requireOperator();
        if (identity.hasPermission("document:read-all")) {
            return;
        }
        if (!documentAclStore.visibleDocumentIds(List.of(documentId), identity).contains(documentId)) {
            throw new SuperAgentFrameException(403, "当前账号没有查看该文档的权限");
        }
    }

    private void requireDocumentWrite(Long documentId) {
        org.smartledge.database.tenant.RequestIdentity identity = requireOperator();
        if (!documentAclStore.writableDocumentIds(List.of(documentId), identity).contains(documentId)) {
            throw new SuperAgentFrameException(403, "当前账号没有修改该文档的权限");
        }
    }

    private org.smartledge.database.tenant.RequestIdentity requireOperator() {
        org.smartledge.database.tenant.RequestIdentity identity =
            org.smartledge.database.tenant.TenantContext.getIdentity();
        if (identity == null) {
            throw new SuperAgentFrameException(401, "请先登录");
        }
        return identity;
    }
}
