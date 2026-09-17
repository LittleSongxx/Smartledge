package org.smartledge.ai.manage.service;

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
import org.smartledge.ai.manage.vo.DocumentProfileVo;
import org.smartledge.ai.manage.vo.KnowledgeRouteTracePageVo;
import org.smartledge.ai.manage.vo.KnowledgeScopeItemVo;
import org.smartledge.ai.manage.vo.KnowledgeTopicItemVo;
import org.smartledge.ai.manage.vo.TopicDocumentRelationItemVo;

import java.util.List;

/**
 * @description: 服务层
 * @author: Song
 **/
public interface KnowledgeManageService {

    KnowledgeScopeItemVo saveScope(KnowledgeScopeSaveDto dto);

    boolean deleteScope(KnowledgeScopeDeleteDto dto);

    List<KnowledgeScopeItemVo> listScopes(KnowledgeScopeQueryDto dto);

    KnowledgeTopicItemVo saveTopic(KnowledgeTopicSaveDto dto);

    boolean deleteTopic(KnowledgeTopicDeleteDto dto);

    List<KnowledgeTopicItemVo> listTopics(KnowledgeTopicQueryDto dto);

    DocumentProfileVo queryProfile(DocumentProfileDetailQueryDto dto);

    DocumentProfileVo regenerateProfile(DocumentProfileRegenerateDto dto);

    List<DocumentProfileVo> batchRegenerateProfiles(DocumentProfileBatchRegenerateDto dto);

    List<TopicDocumentRelationItemVo> listTopicDocuments(TopicDocumentRelationListQueryDto dto);

    TopicDocumentRelationItemVo saveTopicDocumentRelation(TopicDocumentRelationSaveDto dto);

    boolean removeTopicDocumentRelation(TopicDocumentRelationRemoveDto dto);

    KnowledgeRouteTracePageVo queryRouteTracePage(KnowledgeRouteTraceQueryDto dto);
}
