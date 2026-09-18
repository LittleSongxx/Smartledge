package org.smartledge.ai.chatagent.service;

import com.baidu.fsg.uid.UidGenerator;
import org.smartledge.ai.chatagent.data.SuperAgentChatExchangeFeedback;
import org.smartledge.ai.chatagent.dto.ChatExchangeFeedbackDto;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatExchangeFeedbackMapper;
import org.smartledge.ai.chatagent.model.ConversationExchangeView;
import org.smartledge.ai.chatagent.service.ChatExchangeFeedbackService.FeedbackSummary;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.ChatTurnStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮次用户反馈行为锁定：只有会话归属人能评价、只能评价终态轮次、
 * 改评覆盖而不是新增、观测侧取每轮最新一条。
 */
@ExtendWith(MockitoExtension.class)
class ChatExchangeFeedbackServiceTest {

    @Mock
    private SuperAgentChatExchangeFeedbackMapper feedbackMapper;

    @Mock
    private ConversationArchiveStore archiveStore;

    @Mock
    private ConversationAccessGuard accessGuard;

    @Mock
    private UidGenerator uidGenerator;

    private ChatExchangeFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new ChatExchangeFeedbackService(feedbackMapper, archiveStore, accessGuard, uidGenerator);
        org.mockito.Mockito.lenient().doNothing().when(accessGuard).requireOwned(anyString());
        org.mockito.Mockito.lenient().when(accessGuard.requireCurrentUserId()).thenReturn(7L);
        TenantContext.set(3L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ConversationExchangeView exchange(long exchangeId, ChatTurnStatus status) {
        return new ConversationExchangeView(exchangeId, "问题", "答案", List.of(), List.of(), List.of(), List.of(),
            null, status, "", null, null, "NONE", List.of(), List.of(), null, null, null, null, null);
    }

    private ChatExchangeFeedbackDto dto(long exchangeId, String rating) {
        ChatExchangeFeedbackDto dto = new ChatExchangeFeedbackDto();
        dto.setConversationId("conv-1");
        dto.setExchangeId(exchangeId);
        dto.setRating(rating);
        return dto;
    }

    @Test
    @DisplayName("首次评价插入一条反馈并回显评分")
    void insertsFirstFeedback() {
        when(archiveStore.listExchanges("conv-1")).thenReturn(List.of(exchange(100L, ChatTurnStatus.COMPLETED)));
        when(feedbackMapper.selectOne(any())).thenReturn(null);
        when(uidGenerator.getUid()).thenReturn(555L);

        FeedbackSummary summary = service.submit(dto(100L, "DOWN"));

        assertThat(summary.rating()).isEqualTo("DOWN");
        ArgumentCaptor<SuperAgentChatExchangeFeedback> captor =
            ArgumentCaptor.forClass(SuperAgentChatExchangeFeedback.class);
        verify(feedbackMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getTenantId()).isEqualTo(3L);
        assertThat(captor.getValue().getRating()).isEqualTo(-1);
    }

    @Test
    @DisplayName("改评覆盖原值而不是新增一条")
    void updateOverwritesExistingFeedback() {
        when(archiveStore.listExchanges("conv-1")).thenReturn(List.of(exchange(100L, ChatTurnStatus.COMPLETED)));
        SuperAgentChatExchangeFeedback existing = new SuperAgentChatExchangeFeedback();
        existing.setId(555L);
        existing.setExchangeId(100L);
        existing.setUserId(7L);
        existing.setRating(-1);
        when(feedbackMapper.selectOne(any())).thenReturn(existing);

        service.submit(dto(100L, "UP"));

        ArgumentCaptor<SuperAgentChatExchangeFeedback> captor =
            ArgumentCaptor.forClass(SuperAgentChatExchangeFeedback.class);
        verify(feedbackMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(555L);
        assertThat(captor.getValue().getRating()).isEqualTo(1);
        verify(feedbackMapper, never()).insert(any(SuperAgentChatExchangeFeedback.class));
    }

    @Test
    @DisplayName("RUNNING 轮次不能评价")
    void rejectsRunningExchange() {
        when(archiveStore.listExchanges("conv-1")).thenReturn(List.of(exchange(100L, ChatTurnStatus.RUNNING)));

        assertThatThrownBy(() -> service.submit(dto(100L, "UP")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("生成中");
    }

    @Test
    @DisplayName("轮次不属于该会话时拒绝")
    void rejectsUnknownExchange() {
        when(archiveStore.listExchanges("conv-1")).thenReturn(List.of(exchange(100L, ChatTurnStatus.COMPLETED)));

        assertThatThrownBy(() -> service.submit(dto(999L, "UP")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("观测侧按轮次取最新反馈并翻译为 UP/DOWN")
    void latestByExchangeMapsRatings() {
        SuperAgentChatExchangeFeedback up = new SuperAgentChatExchangeFeedback();
        up.setExchangeId(100L);
        up.setRating(1);
        SuperAgentChatExchangeFeedback down = new SuperAgentChatExchangeFeedback();
        down.setExchangeId(101L);
        down.setRating(-1);
        down.setComment("没引用到来源");
        when(feedbackMapper.selectList(any())).thenReturn(List.of(up, down));

        var result = service.latestByExchange(List.of(100L, 101L, 102L));

        assertThat(result.get(100L).rating()).isEqualTo("UP");
        assertThat(result.get(101L).rating()).isEqualTo("DOWN");
        assertThat(result.get(101L).comment()).isEqualTo("没引用到来源");
        assertThat(result).doesNotContainKey(102L);
    }
}
