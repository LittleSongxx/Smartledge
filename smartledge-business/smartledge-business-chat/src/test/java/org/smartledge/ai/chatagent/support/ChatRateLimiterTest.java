package org.smartledge.ai.chatagent.support;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流闸门行为锁定：窗口计数与阈值比较、并发集合的登记/释放/续期脚本形态，
 * 以及配置缺失时回退默认阈值。
 */
class ChatRateLimiterTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RScript script = mock(RScript.class);
    private final SystemConfigProvider systemConfigProvider = mock(SystemConfigProvider.class);

    private ChatRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        SystemConfigSnapshot snapshot = new SystemConfigSnapshot();
        snapshot.getChatRateLimit().setPerMinutePerUser(2);
        snapshot.getChatRateLimit().setConcurrentPerTenant(3);
        when(systemConfigProvider.currentSnapshot()).thenReturn(snapshot);
        rateLimiter = new ChatRateLimiter(redissonClient, systemConfigProvider);
    }

    private void stubEval(Long result) {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(),
            any(Object[].class))).thenReturn(result);
    }

    @Test
    @DisplayName("用户窗口：窗口内计数不超过阈值时放行")
    void userWindowAllowsWithinLimit() {
        stubEval(2L);

        assertThat(rateLimiter.tryAcquireUserWindow(7L, 42L)).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> keys = ArgumentCaptor.forClass(List.class);
        verify(script).eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), keys.capture(),
            any(Object[].class));
        assertThat(keys.getValue()).containsExactly("chat:ratelimit:user:7:42");
    }

    @Test
    @DisplayName("用户窗口：计数超过阈值时拒绝")
    void userWindowRejectsOverLimit() {
        stubEval(3L);

        assertThat(rateLimiter.tryAcquireUserWindow(7L, 42L)).isFalse();
    }

    @Test
    @DisplayName("租户并发：集合未满时登记会话并放行")
    void tenantConcurrencyAcquiresConversation() {
        stubEval(1L);

        assertThat(rateLimiter.tryAcquireTenantConversation(7L, "conv-1")).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> keys = ArgumentCaptor.forClass(List.class);
        verify(script).eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), keys.capture(),
            any(Object[].class));
        assertThat(keys.getValue()).containsExactly("chat:concurrent:7");
    }

    @Test
    @DisplayName("租户并发：集合已满时拒绝")
    void tenantConcurrencyRejectsWhenFull() {
        stubEval(0L);

        assertThat(rateLimiter.tryAcquireTenantConversation(7L, "conv-1")).isFalse();
    }

    @Test
    @DisplayName("释放并发额度使用幂等 SREM 脚本")
    void releaseUsesIdempotentSrem() {
        stubEval(1L);

        rateLimiter.releaseTenantConversation(7L, "conv-1");

        org.mockito.ArgumentCaptor<String> scriptText = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(script).eval(any(RScript.Mode.class), scriptText.capture(), any(RScript.ReturnType.class), anyList(),
            any(Object[].class));
        assertThat(scriptText.getValue()).contains("SREM");
    }

    @Test
    @DisplayName("续期只刷新 TTL，不改变集合成员")
    void refreshOnlyExtendsTtl() {
        stubEval(1L);

        rateLimiter.refreshTenantConversationTtl(7L, "conv-1");

        org.mockito.ArgumentCaptor<String> scriptText = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(script).eval(any(RScript.Mode.class), scriptText.capture(), any(RScript.ReturnType.class), anyList(),
            any(Object[].class));
        assertThat(scriptText.getValue()).contains("PEXPIRE").doesNotContain("SADD", "SREM");
    }

    @Test
    @DisplayName("配置快照缺失时回退默认阈值（每分钟 10 次 / 并发 5 个）")
    void fallsBackToDefaultLimitsWhenSnapshotMissing() {
        when(systemConfigProvider.currentSnapshot()).thenReturn(null);

        stubEval(10L);
        assertThat(rateLimiter.tryAcquireUserWindow(7L, 42L)).isTrue();
        stubEval(11L);
        assertThat(rateLimiter.tryAcquireUserWindow(7L, 42L)).isFalse();

        stubEval(1L);
        assertThat(rateLimiter.tryAcquireTenantConversation(7L, "conv-1")).isTrue();
        stubEval(0L);
        assertThat(rateLimiter.tryAcquireTenantConversation(7L, "conv-1")).isFalse();
    }
}
