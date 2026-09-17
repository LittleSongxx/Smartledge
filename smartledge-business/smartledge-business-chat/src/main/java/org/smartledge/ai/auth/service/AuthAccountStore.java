package org.smartledge.ai.auth.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 认证数据的唯一读取与写入点（用户、角色、权限、租户查找）。
 *
 * <p>它是认证侧的消费方接口，实现必须是唯一的：认证数据只能从这里取，
 * 否则"口令校验用哪一行、权限来自哪里"就会有两个答案。</p>
 *
 * <h2>租户作用域约定</h2>
 *
 * <p>登录时请求还没有租户上下文（租户来自登录请求本身），因此本接口的所有方法
 * 都在**已解析出的租户作用域**内执行，并且语句里同时带显式的 {@code tenant_id} 谓词。
 * 这样租户收窄（MyBatis 拦截器）与显式谓词是同一个值，两条防线不会互相掩盖。</p>
 */
public interface AuthAccountStore {

    /**
     * 按租户编码查找启用中的租户 id。
     *
     * <p>租户表没有 tenant_id 列（它是租户本身的定义），因此这次查找在系统作用域内执行。</p>
     */
    Optional<Long> findEnabledTenantIdByCode(String tenantCode);

    /** 按 (租户, 登录名) 查找启用中的账号。找不到返回空，不抛异常（调用方按"账号不存在"处理）。 */
    Optional<AuthAccount> findEnabledAccount(Long tenantId, String username);

    /** 账号在该租户内启用中的角色 id。 */
    List<Long> listEnabledRoleIds(Long tenantId, Long userId);

    /** 这些角色在该租户内启用中的权限编码。角色为空时返回空集合（不是全部权限）。 */
    Set<String> listEnabledPermissionCodes(Long tenantId, Collection<Long> roleIds);

    /**
     * 记录一次登录失败：失败计数 +1，并按传入值写入或清除锁定截止时间。
     *
     * @param lockedUntil 达到阈值时的锁定截止时间；未达阈值传 {@code null}（表示清除锁定）
     */
    void recordFailedAttempt(Long tenantId, Long userId, Instant lockedUntil);

    /** 记录一次登录成功：失败计数清零、解除锁定、写入最近登录时间。 */
    void recordSuccessfulLogin(Long tenantId, Long userId, Instant loginAt);

    /**
     * 账号快照。
     *
     * @param failedAttempts 连续失败次数
     * @param lockedUntil    锁定截止时间；{@code null} 表示未锁定
     */
    record AuthAccount(Long tenantId,
                       Long userId,
                       String username,
                       String passwordHash,
                       int failedAttempts,
                       Instant lockedUntil) {

        /** 当前时刻是否处于锁定期。 */
        public boolean lockedAt(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }
}
