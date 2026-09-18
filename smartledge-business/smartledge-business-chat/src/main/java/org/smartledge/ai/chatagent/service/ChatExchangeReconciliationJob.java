package org.smartledge.ai.chatagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.data.SuperAgentChatDialogue;
import org.smartledge.ai.chatagent.data.SuperAgentChatExchange;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatDialogueMapper;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatExchangeMapper;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.ChatTurnStatus;
import org.smartledge.lease.RedisLeaseManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天轮次孤儿对账：进程崩溃后 {@code RUNNING} 轮次与会话会永久挂起的唯一兜底。
 *
 * <p>判定孤儿用两个条件的**与**，缺一不可：</p>
 * <ul>
 *   <li>轮次仍是 RUNNING 且 edit_time 早于阈值（廉价预筛，避开正在落部分答案的热行）；</li>
 *   <li>该会话的 Redis 运行租约已消失。租约 TTL 30s、每 10s 续期，活着的执行一定持有租约；
 *       租约消失说明执行进程已死或已崩溃。只看时间会误杀"检索阶段长时间无 token"的活轮次，
 *       租约检查才是权威。</li>
 * </ul>
 *
 * <p>收口动作与失败终态同一条路径（{@code completeExchange}）：保留已落库的部分答案、
 * 轮次置 FAILED、会话置 IDLE。对账本身用 Redis 租约互斥，多实例只有一个执行。</p>
 */
@Slf4j
@Component
public class ChatExchangeReconciliationJob {

    private static final String RECONCILE_LEASE_KEY = "chat:exchange:reconcile:lease";
    private static final Duration RECONCILE_LEASE_TTL = Duration.ofSeconds(55);

    private final SuperAgentChatExchangeMapper exchangeMapper;
    private final SuperAgentChatDialogueMapper dialogueMapper;
    private final ConversationArchiveStore conversationArchiveStore;
    private final RedisLeaseManager redisLeaseManager;
    private final StringRedisTemplate stringRedisTemplate;
    private final long staleMillis;
    private final int batchSize;

    public ChatExchangeReconciliationJob(SuperAgentChatExchangeMapper exchangeMapper,
                                         SuperAgentChatDialogueMapper dialogueMapper,
                                         ConversationArchiveStore conversationArchiveStore,
                                         RedisLeaseManager redisLeaseManager,
                                         StringRedisTemplate stringRedisTemplate,
                                         @Value("${app.chat.reconcile.stale-millis:90000}") long staleMillis,
                                         @Value("${app.chat.reconcile.batch-size:100}") int batchSize) {
        this.exchangeMapper = exchangeMapper;
        this.dialogueMapper = dialogueMapper;
        this.conversationArchiveStore = conversationArchiveStore;
        this.redisLeaseManager = redisLeaseManager;
        this.stringRedisTemplate = stringRedisTemplate;
        this.staleMillis = staleMillis;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${app.chat.reconcile.interval-millis:60000}",
        initialDelayString = "${app.chat.reconcile.interval-millis:60000}")
    public void reconcile() {
        // 对账跨租户扫描系统表，必须显式系统上下文；租户拦截器 fail closed。
        TenantContext.runAsSystem(this::doReconcile);
    }

    private void doReconcile() {
        String ownerToken = UUID.randomUUID().toString();
        if (!redisLeaseManager.acquire(RECONCILE_LEASE_KEY, ownerToken, RECONCILE_LEASE_TTL)) {
            return;
        }
        try {
            Date cutoff = new Date(System.currentTimeMillis() - staleMillis);
            List<SuperAgentChatExchange> candidates = exchangeMapper.selectList(
                new LambdaQueryWrapper<SuperAgentChatExchange>()
                    .eq(SuperAgentChatExchange::getTurnStatus, ChatTurnStatus.RUNNING.getCode())
                    .eq(SuperAgentChatExchange::getStatus, BusinessStatus.YES.getCode())
                    .lt(SuperAgentChatExchange::getEditTime, cutoff)
                    .last("LIMIT " + batchSize));
            if (candidates.isEmpty()) {
                return;
            }
            Map<String, Long> tenantByConversation = tenantIdByConversation(candidates);
            for (SuperAgentChatExchange candidate : candidates) {
                closeIfOrphaned(candidate, tenantByConversation.get(candidate.getConversationId()));
            }
        }
        catch (RuntimeException exception) {
            log.warn("聊天轮次对账执行失败", exception);
        }
        finally {
            redisLeaseManager.release(RECONCILE_LEASE_KEY, ownerToken);
        }
    }

    private Map<String, Long> tenantIdByConversation(List<SuperAgentChatExchange> candidates) {
        Set<String> conversationIds = candidates.stream()
            .map(SuperAgentChatExchange::getConversationId)
            .collect(Collectors.toSet());
        return dialogueMapper.selectList(
                new LambdaQueryWrapper<SuperAgentChatDialogue>()
                    .in(SuperAgentChatDialogue::getConversationId, conversationIds))
            .stream()
            .filter(dialogue -> dialogue.getTenantId() != null)
            .collect(Collectors.toMap(SuperAgentChatDialogue::getConversationId,
                SuperAgentChatDialogue::getTenantId, (first, second) -> first));
    }

    private void closeIfOrphaned(SuperAgentChatExchange exchange, Long tenantId) {
        if (tenantId == null) {
            log.warn("跳过无法解析租户的孤儿轮次, conversationId={}, exchangeId={}",
                exchange.getConversationId(), exchange.getId());
            return;
        }
        Boolean leaseAlive = TenantContext.callAsSystem(() ->
            stringRedisTemplate.hasKey(ConversationRuntimeKeys.leaseKey(tenantId, exchange.getConversationId())));
        if (Boolean.TRUE.equals(leaseAlive)) {
            // 租约还在就说明执行进程活着（长检索阶段可能长时间没有 token），绝不收口。
            return;
        }
        String answer = exchange.getAnswer() == null ? "" : exchange.getAnswer();
        Long totalMillis = exchange.getCreateTime() == null
            ? null
            : System.currentTimeMillis() - exchange.getCreateTime().getTime();
        TenantContext.runWith(tenantId, () -> conversationArchiveStore.completeExchange(
            exchange.getConversationId(),
            exchange.getId(),
            answer,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            ChatTurnStatus.FAILED,
            "会话执行中断（服务重启或异常退出），已自动收口。",
            null,
            totalMillis
        ));
        log.warn("孤儿轮次已对账收口, tenantId={}, conversationId={}, exchangeId={}, partialAnswerChars={}",
            tenantId, exchange.getConversationId(), exchange.getId(), answer.length());
    }
}
