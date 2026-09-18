package org.smartledge.ai.chatagent.support;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 流式问答入口的分布式限流闸门（Redis Lua，与 RedisLeaseManager 同一套风格）。
 *
 * <p>两层保护，都在抢会话租约之前执行：</p>
 * <ul>
 *   <li><b>用户频率</b>：tenant+user 维度的固定窗口计数，防止单用户刷接口消耗模型预算；</li>
 *   <li><b>租户并发</b>：tenant 维度正在执行的会话集合（SADD/SREM），防止一个租户占满
 *       全局模型线程池。成员是 conversationId——同一会话本来就被租约串行化，
 *       并发闸门数的是"同时运行的会话数"。SREM 天然幂等，重复释放无副作用。</li>
 * </ul>
 *
 * <p>并发集合带 TTL 并随租约续期刷新：进程崩溃后集合随 TTL 过期自愈，
 * 最坏情况是短暂多放行一个会话，方向上可接受（这是保护闸门，不是配额账本）。</p>
 */
@Component
public class ChatRateLimiter {

    private static final String USER_WINDOW_SCRIPT =
        "local current = redis.call('INCR', KEYS[1]) "
            + "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
            + "return current";

    private static final String CONCURRENCY_ACQUIRE_SCRIPT =
        "if redis.call('SCARD', KEYS[1]) < tonumber(ARGV[1]) then "
            + "redis.call('SADD', KEYS[1], ARGV[2]) "
            + "redis.call('PEXPIRE', KEYS[1], ARGV[3]) "
            + "return 1 "
            + "end "
            + "return 0";

    private static final String CONCURRENCY_RELEASE_SCRIPT =
        "redis.call('SREM', KEYS[1], ARGV[1]) "
            + "if redis.call('SCARD', KEYS[1]) == 0 then redis.call('DEL', KEYS[1]) "
            + "else redis.call('PEXPIRE', KEYS[1], ARGV[2]) end "
            + "return 1";

    private static final String USER_WINDOW_KEY_PREFIX = "chat:ratelimit:user:";
    private static final String TENANT_CONCURRENCY_KEY_PREFIX = "chat:concurrent:";
    /** 固定窗口长度：与"每分钟"语义一致。 */
    private static final long USER_WINDOW_MILLIS = 60_000L;
    /** 并发集合 TTL：需大于会话租约 TTL(30s) + 续期间隔(10s)，由续期路径持续刷新。 */
    private static final long CONCURRENCY_TTL_MILLIS = 120_000L;

    private final RedissonClient redissonClient;

    private final SystemConfigProvider systemConfigProvider;

    public ChatRateLimiter(RedissonClient redissonClient, SystemConfigProvider systemConfigProvider) {
        this.redissonClient = redissonClient;
        this.systemConfigProvider = systemConfigProvider;
    }

    /** 用户固定窗口：本分钟内第 N 次提问，N 超过上限时拒绝。 */
    public boolean tryAcquireUserWindow(Long tenantId, Long userId) {
        Assert.notNull(tenantId, "tenantId 不能为空");
        Assert.notNull(userId, "userId 不能为空");
        int limit = limits().getPerMinutePerUser();
        Long current = eval(USER_WINDOW_SCRIPT, userWindowKey(tenantId, userId),
            String.valueOf(USER_WINDOW_MILLIS));
        long observed = current == null ? 0L : current;
        return observed <= limit;
    }

    /** 租户并发会话：并发集合未满则登记本会话并放行。 */
    public boolean tryAcquireTenantConversation(Long tenantId, String conversationId) {
        Assert.notNull(tenantId, "tenantId 不能为空");
        Assert.hasText(conversationId, "conversationId 不能为空");
        int limit = limits().getConcurrentPerTenant();
        Long acquired = eval(CONCURRENCY_ACQUIRE_SCRIPT, tenantConcurrencyKey(tenantId),
            String.valueOf(limit), conversationId, String.valueOf(CONCURRENCY_TTL_MILLIS));
        return acquired != null && acquired == 1L;
    }

    /** 会话终态释放并发额度；幂等，可安全重复调用。 */
    public void releaseTenantConversation(Long tenantId, String conversationId) {
        Assert.notNull(tenantId, "tenantId 不能为空");
        Assert.hasText(conversationId, "conversationId 不能为空");
        eval(CONCURRENCY_RELEASE_SCRIPT, tenantConcurrencyKey(tenantId),
            conversationId, String.valueOf(CONCURRENCY_TTL_MILLIS));
    }

    /** 随租约续期刷新并发集合 TTL，避免长会话执行中额度自愈成 0。 */
    public void refreshTenantConversationTtl(Long tenantId, String conversationId) {
        Assert.notNull(tenantId, "tenantId 不能为空");
        Assert.hasText(conversationId, "conversationId 不能为空");
        // SADD 已存在的成员是 no-op，只借这次调用刷新 TTL。
        eval("redis.call('PEXPIRE', KEYS[1], ARGV[1]) if redis.call('EXISTS', KEYS[1]) == 1 then return 1 end return 0",
            tenantConcurrencyKey(tenantId), String.valueOf(CONCURRENCY_TTL_MILLIS));
    }

    private SystemConfigSnapshot.ChatRateLimitOptions limits() {
        SystemConfigSnapshot snapshot = systemConfigProvider.currentSnapshot();
        if (snapshot == null || snapshot.getChatRateLimit() == null) {
            return new SystemConfigSnapshot.ChatRateLimitOptions();
        }
        return snapshot.getChatRateLimit();
    }

    private Long eval(String script, String key, String... args) {
        return redissonClient.getScript(StringCodec.INSTANCE).eval(
            RScript.Mode.READ_WRITE,
            script,
            RScript.ReturnType.INTEGER,
            java.util.List.of(key),
            args
        );
    }

    private String userWindowKey(Long tenantId, Long userId) {
        return USER_WINDOW_KEY_PREFIX + tenantId + ":" + userId;
    }

    private String tenantConcurrencyKey(Long tenantId) {
        return TENANT_CONCURRENCY_KEY_PREFIX + tenantId;
    }
}
