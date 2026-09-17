package org.smartledge.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.smartledge.ai.chatagent.data.SuperAgentChatChannelExecution;
import org.smartledge.ai.chatagent.data.SuperAgentChatRetrievalResult;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatChannelExecutionMapper;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatRetrievalResultMapper;
import org.smartledge.ai.chatagent.model.ChannelExecutionView;
import org.smartledge.ai.chatagent.model.RetrievalResultView;
import org.smartledge.enums.BusinessStatus;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.JdbcUpdateAffectedIncorrectNumberOfRowsException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: 服务层
 * @author: Song
 **/

@Repository
public class MybatisRetrievalObserveStore implements RetrievalObserveStore {

    private static final TypeReference<LinkedHashMap<String, Object>> CONFIG_TYPE = new TypeReference<>() {
    };

    private final SuperAgentChatRetrievalResultMapper retrievalResultMapper;
    private final SuperAgentChatChannelExecutionMapper channelExecutionMapper;
    private final ObjectMapper objectMapper;

    @Resource
    private UidGenerator uidGenerator;

    public MybatisRetrievalObserveStore(SuperAgentChatRetrievalResultMapper retrievalResultMapper,
                                        SuperAgentChatChannelExecutionMapper channelExecutionMapper,
                                        ObjectMapper objectMapper) {
        this.retrievalResultMapper = retrievalResultMapper;
        this.channelExecutionMapper = channelExecutionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchSaveResults(String conversationId, long exchangeId, List<RetrievalResultView> results) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        for (RetrievalResultView view : results) {
            if (view == null || StrUtil.isBlank(view.getCandidateId())) {
                throw new IllegalArgumentException("Retrieval observation candidateId must not be blank");
            }
        }
        for (RetrievalResultView view : results) {
            SuperAgentChatRetrievalResult entity = new SuperAgentChatRetrievalResult();
            entity.setId(uidGenerator.getUid());
            entity.setConversationId(conversationId);
            entity.setExchangeId(exchangeId);
            entity.setTraceId(view.getTraceId());
            entity.setSubQuestionIndex(view.getSubQuestionIndex());
            entity.setSubQuestion(view.getSubQuestion());
            entity.setCandidateId(view.getCandidateId());
            entity.setChannelType(view.getChannelType());
            entity.setChannelRank(view.getChannelRank());
            entity.setRrfRank(view.getRrfRank());
            entity.setFinalRank(view.getFinalRank());
            entity.setOriginalScore(view.getOriginalScore());
            entity.setRrfScore(view.getRrfScore());
            entity.setHybridScore(view.getHybridScore());
            entity.setMetadataBoost(view.getMetadataBoost());
            entity.setVectorScore(view.getVectorScore());
            entity.setKeywordScore(view.getKeywordScore());
            entity.setRerankScore(view.getRerankScore());
            entity.setGatePassed(view.isGatePassed() ? 1 : 0);
            entity.setIsElevated(view.isElevated() ? 1 : 0);
            entity.setIsSelected(view.isSelected() ? 1 : 0);
            entity.setSelectionReason(view.getSelectionReason());
            entity.setFilteredReason(view.getFilteredReason());
            entity.setRankFeature(view.getRankFeature());
            entity.setDocumentId(view.getDocumentId());
            entity.setDocumentName(view.getDocumentName());
            entity.setChunkId(view.getChunkId());
            entity.setChunkType(view.getChunkType());
            entity.setChunkNo(view.getChunkNo());
            entity.setParentBlockId(view.getParentBlockId());
            entity.setParentBlockNo(view.getParentBlockNo());
            entity.setSectionPath(view.getSectionPath());
            entity.setChunkTextPreview(view.getChunkTextPreview());
            entity.setChunkCharCount(view.getChunkCharCount());
            entity.setContextIdentity(view.getContextIdentity());
            entity.setCitationIdentity(view.getCitationIdentity());
            entity.setCitationIdentityHash(citationIdentityHash(view.getCitationIdentity()));
            entity.setCitationEvidenceType(view.getCitationEvidenceType());
            entity.setContextOnly(view.isContextOnly() ? 1 : 0);
            entity.setSourceEvidenceResolved(view.isSourceEvidenceResolved() ? 1 : 0);
            entity.setStatus(BusinessStatus.YES.getCode());
            int insertedCount = retrievalResultMapper.insert(entity);
            if (insertedCount != 1) {
                throw new JdbcUpdateAffectedIncorrectNumberOfRowsException(
                    "INSERT smartledge_chat_retrieval_result",
                    1,
                    insertedCount
                );
            }
        }
        return results.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchSaveChannelExecutions(String conversationId, long exchangeId, List<ChannelExecutionView> executions) {
        if (executions == null || executions.isEmpty()) {
            return;
        }
        for (ChannelExecutionView view : executions) {
            SuperAgentChatChannelExecution entity = new SuperAgentChatChannelExecution();
            entity.setId(uidGenerator.getUid());
            entity.setConversationId(conversationId);
            entity.setExchangeId(exchangeId);
            entity.setTraceId(view.getTraceId());
            entity.setSubQuestionIndex(view.getSubQuestionIndex());
            entity.setSubQuestion(view.getSubQuestion());
            entity.setChannelType(view.getChannelType());
            entity.setExecutionState(view.getExecutionState());
            entity.setStartTime(view.getStartTime() == null ? null : Date.from(view.getStartTime()));
            entity.setEndTime(view.getEndTime() == null ? null : Date.from(view.getEndTime()));
            entity.setDurationMs(view.getDurationMs());
            entity.setRecalledCount(view.getRecalledCount());
            entity.setAcceptedCount(view.getAcceptedCount());
            entity.setFinalSelectedCount(view.getFinalSelectedCount());
            entity.setAvgScore(view.getAvgScore());
            entity.setMaxScore(view.getMaxScore());
            entity.setMinScore(view.getMinScore());
            entity.setConfigSnapshot(writeConfigSnapshot(view.getConfigSnapshot()));
            entity.setErrorMessage(view.getErrorMessage());
            entity.setStatus(BusinessStatus.YES.getCode());
            channelExecutionMapper.insert(entity);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetrievalResultView> listResults(String conversationId, long exchangeId) {
        return retrievalResultMapper.selectList(
                new LambdaQueryWrapper<SuperAgentChatRetrievalResult>()
                    .eq(SuperAgentChatRetrievalResult::getConversationId, conversationId)
                    .eq(SuperAgentChatRetrievalResult::getExchangeId, exchangeId)
                    .orderByAsc(SuperAgentChatRetrievalResult::getSubQuestionIndex)
                    .orderByAsc(SuperAgentChatRetrievalResult::getFinalRank)
            )
            .stream()
            .map(this::toResultView)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChannelExecutionView> listChannelExecutions(String conversationId, long exchangeId) {
        return channelExecutionMapper.selectList(
                new LambdaQueryWrapper<SuperAgentChatChannelExecution>()
                    .eq(SuperAgentChatChannelExecution::getConversationId, conversationId)
                    .eq(SuperAgentChatChannelExecution::getExchangeId, exchangeId)
                    .orderByAsc(SuperAgentChatChannelExecution::getSubQuestionIndex)
                    .orderByAsc(SuperAgentChatChannelExecution::getStartTime)
            )
            .stream()
            .map(this::toExecutionView)
            .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByConversation(String conversationId) {
        retrievalResultMapper.delete(
            new LambdaQueryWrapper<SuperAgentChatRetrievalResult>()
                .eq(SuperAgentChatRetrievalResult::getConversationId, conversationId)
        );
        channelExecutionMapper.delete(
            new LambdaQueryWrapper<SuperAgentChatChannelExecution>()
                .eq(SuperAgentChatChannelExecution::getConversationId, conversationId)
        );
    }

    private RetrievalResultView toResultView(SuperAgentChatRetrievalResult entity) {
        return new RetrievalResultView(
            entity.getId(),
            StrUtil.blankToDefault(entity.getTraceId(), ""),
            entity.getSubQuestionIndex() == null ? 1 : entity.getSubQuestionIndex(),
            StrUtil.blankToDefault(entity.getSubQuestion(), ""),
            StrUtil.blankToDefault(entity.getCandidateId(), ""),
            StrUtil.blankToDefault(entity.getChannelType(), ""),
            entity.getChannelRank(),
            entity.getRrfRank(),
            entity.getFinalRank(),
            entity.getOriginalScore(),
            entity.getRrfScore(),
            entity.getHybridScore(),
            entity.getMetadataBoost(),
            entity.getVectorScore(),
            entity.getKeywordScore(),
            entity.getRerankScore(),
            entity.getGatePassed() != null && entity.getGatePassed() == 1,
            entity.getIsElevated() != null && entity.getIsElevated() == 1,
            entity.getIsSelected() != null && entity.getIsSelected() == 1,
            StrUtil.blankToDefault(entity.getSelectionReason(), ""),
            StrUtil.blankToDefault(entity.getFilteredReason(), ""),
            StrUtil.blankToDefault(entity.getRankFeature(), ""),
            entity.getDocumentId(),
            StrUtil.blankToDefault(entity.getDocumentName(), ""),
            entity.getChunkId(),
            StrUtil.blankToDefault(entity.getChunkType(), ""),
            entity.getChunkNo(),
            entity.getParentBlockId(),
            entity.getParentBlockNo(),
            StrUtil.blankToDefault(entity.getSectionPath(), ""),
            StrUtil.blankToDefault(entity.getChunkTextPreview(), ""),
            entity.getChunkCharCount(),
            StrUtil.blankToDefault(entity.getContextIdentity(), ""),
            StrUtil.blankToDefault(entity.getCitationIdentity(), ""),
            StrUtil.blankToDefault(entity.getCitationIdentityHash(), ""),
            StrUtil.blankToDefault(entity.getCitationEvidenceType(), ""),
            entity.getContextOnly() != null && entity.getContextOnly() == 1,
            entity.getSourceEvidenceResolved() != null && entity.getSourceEvidenceResolved() == 1,
            toInstant(entity.getCreateTime())
        );
    }

    private ChannelExecutionView toExecutionView(SuperAgentChatChannelExecution entity) {
        return new ChannelExecutionView(
            entity.getId(),
            StrUtil.blankToDefault(entity.getTraceId(), ""),
            entity.getSubQuestionIndex() == null ? 1 : entity.getSubQuestionIndex(),
            StrUtil.blankToDefault(entity.getSubQuestion(), ""),
            StrUtil.blankToDefault(entity.getChannelType(), ""),
            entity.getExecutionState() == null ? 1 : entity.getExecutionState(),
            toInstant(entity.getStartTime()),
            toInstant(entity.getEndTime()),
            entity.getDurationMs(),
            entity.getRecalledCount() == null ? 0 : entity.getRecalledCount(),
            entity.getAcceptedCount() == null ? 0 : entity.getAcceptedCount(),
            entity.getFinalSelectedCount() == null ? 0 : entity.getFinalSelectedCount(),
            entity.getAvgScore(),
            entity.getMaxScore(),
            entity.getMinScore(),
            readConfigSnapshot(entity.getConfigSnapshot()),
            StrUtil.blankToDefault(entity.getErrorMessage(), ""),
            toInstant(entity.getCreateTime())
        );
    }

    private Instant toInstant(Date value) {
        return value == null ? null : value.toInstant();
    }

    private String citationIdentityHash(String citationIdentity) {
        if (citationIdentity == null || citationIdentity.isEmpty()) {
            return null;
        }
        return DigestUtil.sha256Hex(citationIdentity.getBytes(StandardCharsets.UTF_8));
    }

    private String writeConfigSnapshot(Map<String, Object> configSnapshot) {
        if (configSnapshot == null || configSnapshot.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(configSnapshot);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化检索通道配置快照失败", exception);
        }
    }

    private Map<String, Object> readConfigSnapshot(String configSnapshot) {
        if (StrUtil.isBlank(configSnapshot)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(configSnapshot, CONFIG_TYPE);
        }
        catch (JsonProcessingException exception) {
            return Map.of("invalidConfigSnapshot", configSnapshot);
        }
    }
}
