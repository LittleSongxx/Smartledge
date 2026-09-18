package org.smartledge.ai.chatagent.service;

import org.smartledge.ai.chatagent.data.SuperAgentChatDialogue;
import org.smartledge.ai.chatagent.data.SuperAgentChatExchange;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatDialogueMapper;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatExchangeMapper;
import org.smartledge.enums.ChatTurnStatus;
import org.smartledge.lease.RedisLeaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 孤儿轮次对账的行为锁定：
 * 对账租约互斥、RUNNING+租约消失双条件收口、租约仍存活绝不误杀、租户解析失败跳过。
 */
class ChatExchangeReconciliationJobTest {

    private final SuperAgentChatExchangeMapper exchangeMapper = mock(SuperAgentChatExchangeMapper.class);
    private final SuperAgentChatDialogueMapper dialogueMapper = mock(SuperAgentChatDialogueMapper.class);
    private final ConversationArchiveStore archiveStore = mock(ConversationArchiveStore.class);
    private final RedisLeaseManager leaseManager = mock(RedisLeaseManager.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private ChatExchangeReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new ChatExchangeReconciliationJob(exchangeMapper, dialogueMapper, archiveStore,
            leaseManager, redisTemplate, 90_000L, 100);
        when(leaseManager.acquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    private SuperAgentChatExchange runningExchange(String conversationId) {
        SuperAgentChatExchange exchange = new SuperAgentChatExchange();
        exchange.setId(900L);
        exchange.setConversationId(conversationId);
        exchange.setAnswer("已生成的部分答案");
        exchange.setTurnStatus(ChatTurnStatus.RUNNING.getCode());
        exchange.setCreateTime(new Date(System.currentTimeMillis() - 120_000L));
        exchange.setEditTime(new Date(System.currentTimeMillis() - 120_000L));
        return exchange;
    }

    private SuperAgentChatDialogue dialogueOf(String conversationId, Long tenantId) {
        SuperAgentChatDialogue dialogue = new SuperAgentChatDialogue();
        dialogue.setConversationId(conversationId);
        dialogue.setTenantId(tenantId);
        return dialogue;
    }

    @Test
    @DisplayName("对账租约抢不到时本实例直接跳过")
    void skipsWhenReconcileLeaseNotAcquired() {
        when(leaseManager.acquire(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        job.reconcile();

        verifyNoInteractions(exchangeMapper);
        verify(leaseManager, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("会话运行租约仍存活（长检索阶段）时绝不收口")
    void neverClosesExchangeWithAliveLease() {
        when(exchangeMapper.selectList(any())).thenReturn(List.of(runningExchange("conv-1")));
        when(dialogueMapper.selectList(any())).thenReturn(List.of(dialogueOf("conv-1", 7L)));
        when(redisTemplate.hasKey("chat:running:7:conv-1")).thenReturn(true);

        job.reconcile();

        verify(archiveStore, never()).completeExchange(anyString(), eq(900L), anyString(),
            any(), any(), any(), any(), isNull(), any(), anyString(), isNull(), isNull());
    }

    @Test
    @DisplayName("RUNNING 且租约已消失的轮次按 FAILED 收口并保留部分答案")
    void closesOrphanedExchangeAsFailedWithPartialAnswer() {
        when(exchangeMapper.selectList(any())).thenReturn(List.of(runningExchange("conv-1")));
        when(dialogueMapper.selectList(any())).thenReturn(List.of(dialogueOf("conv-1", 7L)));
        when(redisTemplate.hasKey("chat:running:7:conv-1")).thenReturn(false);

        job.reconcile();

        verify(archiveStore).completeExchange(
            eq("conv-1"), eq(900L), eq("已生成的部分答案"),
            any(), any(), any(), any(), isNull(),
            eq(ChatTurnStatus.FAILED), eq("会话执行中断（服务重启或异常退出），已自动收口。"),
            isNull(), any());
    }

    @Test
    @DisplayName("无法从会话解析租户的孤儿轮次只告警不收口")
    void skipsExchangeWithoutResolvableTenant() {
        when(exchangeMapper.selectList(any())).thenReturn(List.of(runningExchange("conv-1")));
        when(dialogueMapper.selectList(any())).thenReturn(List.of());

        job.reconcile();

        verify(archiveStore, never()).completeExchange(anyString(), eq(900L), anyString(),
            any(), any(), any(), any(), isNull(), any(), anyString(), isNull(), isNull());
    }

    @Test
    @DisplayName("没有候选轮次时不做会话查询且释放对账租约")
    void releasesLeaseWhenNoCandidates() {
        when(exchangeMapper.selectList(any())).thenReturn(List.of());

        job.reconcile();

        verifyNoInteractions(dialogueMapper);
        verify(leaseManager).release(eq("chat:exchange:reconcile:lease"), anyString());
    }
}
