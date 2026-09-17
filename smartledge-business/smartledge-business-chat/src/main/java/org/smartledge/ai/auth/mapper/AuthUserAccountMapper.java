package org.smartledge.ai.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.smartledge.ai.auth.data.AuthUserAccount;

import java.util.Date;

/**
 * 用户账号 Mapper。
 *
 * <p>失败计数与锁定状态用原子 UPDATE 维护，而不是"读出来 + 写回去"：
 * 并发登录尝试下后者会丢计数，而失败计数是防暴力破解的依据。</p>
 */
@Mapper
public interface AuthUserAccountMapper extends BaseMapper<AuthUserAccount> {

    /**
     * 失败次数 +1，并按传入值写入或清除锁定截止时间。
     *
     * <p>语句自带显式 {@code tenant_id} 谓词：即使调用方忘了声明租户作用域，
     * 也不会命中其它租户的同名账号。</p>
     */
    @Update("""
        UPDATE smartledge_user
           SET failed_attempts = failed_attempts + 1,
               locked_until = #{lockedUntil},
               edit_time = NOW()
         WHERE tenant_id = #{tenantId}
           AND id = #{userId}
           AND status = 1
        """)
    int recordFailedAttempt(@Param("tenantId") Long tenantId,
                            @Param("userId") Long userId,
                            @Param("lockedUntil") Date lockedUntil);

    /**
     * 登录成功：失败次数清零、解除锁定、记录最近登录时间。
     *
     * <p>{@code locked_until = NULL} 必须走原语句：MyBatis-Plus 的 {@code updateById}
     * 默认忽略 null 字段，用它清不掉锁定。</p>
     */
    @Update("""
        UPDATE smartledge_user
           SET failed_attempts = 0,
               locked_until = NULL,
               last_login_at = #{loginAt},
               edit_time = NOW()
         WHERE tenant_id = #{tenantId}
           AND id = #{userId}
           AND status = 1
        """)
    int recordSuccessfulLogin(@Param("tenantId") Long tenantId,
                             @Param("userId") Long userId,
                             @Param("loginAt") Date loginAt);

    /**
     * 令牌版本 +1，使该用户全部未过期 JWT 立即失效。
     */
    @Update("""
        UPDATE smartledge_user
           SET token_version = IFNULL(token_version, 1) + 1,
               edit_time = NOW()
         WHERE tenant_id = #{tenantId}
           AND id = #{userId}
        """)
    int incrementTokenVersion(@Param("tenantId") Long tenantId,
                              @Param("userId") Long userId);
}
