package org.smartledge.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.data.SuperAgentChatExchangeFeedback;
import org.smartledge.ai.chatagent.dto.ChatExchangeFeedbackDto;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatExchangeFeedbackMapper;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.ChatTurnStatus;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轮次用户反馈：质量回路的线上数据入口。
 *
 * <p>只允许会话归属用户评价自己的轮次，且轮次必须已进入终态（RUNNING 的答案还在变）。
 * 一个用户对一轮只保留一条反馈，改评覆盖。观测侧读取的是最新一条反馈，代表用户信号。</p>
 */
@Slf4j
@Service
public class ChatExchangeFeedbackService {

    /** 反馈摘要：rating 固定为 "UP"/"DOWN"，无反馈为 null。 */
    public record FeedbackSummary(String rating, String comment) {

        public static FeedbackSummary none() {
            return new FeedbackSummary(null, null);
        }
    }

    private final SuperAgentChatExchangeFeedbackMapper feedbackMapper;
    private final ConversationArchiveStore conversationArchiveStore;
    private final ConversationAccessGuard conversationAccessGuard;
    private final UidGenerator uidGenerator;

    public ChatExchangeFeedbackService(SuperAgentChatExchangeFeedbackMapper feedbackMapper,
                                       ConversationArchiveStore conversationArchiveStore,
                                       ConversationAccessGuard conversationAccessGuard,
                                       UidGenerator uidGenerator) {
        this.feedbackMapper = feedbackMapper;
        this.conversationArchiveStore = conversationArchiveStore;
        this.conversationAccessGuard = conversationAccessGuard;
        this.uidGenerator = uidGenerator;
    }

    public FeedbackSummary submit(ChatExchangeFeedbackDto dto) {
        conversationAccessGuard.requireOwned(dto.getConversationId());
        Long userId = conversationAccessGuard.requireCurrentUserId();

        var exchange = conversationArchiveStore.listExchanges(dto.getConversationId()).stream()
            .filter(item -> item != null && item.getExchangeId() == dto.getExchangeId())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("轮次不存在或不属于该会话: " + dto.getExchangeId()));
        if (exchange.getStatus() == ChatTurnStatus.RUNNING) {
            throw new IllegalStateException("轮次仍在生成中，暂不能评价");
        }

        int rating = "UP".equals(dto.getRating()) ? 1 : -1;
        SuperAgentChatExchangeFeedback existing = feedbackMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentChatExchangeFeedback>()
                .eq(SuperAgentChatExchangeFeedback::getExchangeId, dto.getExchangeId())
                .eq(SuperAgentChatExchangeFeedback::getUserId, userId)
                .last("LIMIT 1"));
        if (existing == null) {
            SuperAgentChatExchangeFeedback feedback = new SuperAgentChatExchangeFeedback();
            feedback.setId(uidGenerator.getUid());
            feedback.setConversationId(dto.getConversationId());
            feedback.setExchangeId(dto.getExchangeId());
            feedback.setUserId(userId);
            feedback.setTenantId(TenantContext.get());
            feedback.setRating(rating);
            feedback.setComment(StrUtil.blankToDefault(dto.getComment(), null));
            feedback.setStatus(BusinessStatus.YES.getCode());
            feedbackMapper.insert(feedback);
        }
        else {
            SuperAgentChatExchangeFeedback update = new SuperAgentChatExchangeFeedback();
            update.setId(existing.getId());
            update.setRating(rating);
            update.setComment(StrUtil.blankToDefault(dto.getComment(), null));
            feedbackMapper.updateById(update);
        }
        log.info("轮次用户反馈已保存, conversationId={}, exchangeId={}, userId={}, rating={}",
            dto.getConversationId(), dto.getExchangeId(), userId, dto.getRating());
        return new FeedbackSummary(dto.getRating(), StrUtil.blankToDefault(dto.getComment(), null));
    }

    /** 观测侧：每轮最新一条反馈（租户内任意用户）。 */
    public Map<Long, FeedbackSummary> latestByExchange(Collection<Long> exchangeIds) {
        Map<Long, FeedbackSummary> result = new LinkedHashMap<>();
        if (exchangeIds == null || exchangeIds.isEmpty()) {
            return result;
        }
        List<SuperAgentChatExchangeFeedback> rows = feedbackMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatExchangeFeedback>()
                .in(SuperAgentChatExchangeFeedback::getExchangeId, exchangeIds)
                .orderByDesc(SuperAgentChatExchangeFeedback::getEditTime)
                .orderByDesc(SuperAgentChatExchangeFeedback::getId));
        for (SuperAgentChatExchangeFeedback row : rows) {
            result.putIfAbsent(row.getExchangeId(), new FeedbackSummary(
                row.getRating() != null && row.getRating() > 0 ? "UP" : "DOWN",
                StrUtil.blankToDefault(row.getComment(), null)));
        }
        return result;
    }
}
