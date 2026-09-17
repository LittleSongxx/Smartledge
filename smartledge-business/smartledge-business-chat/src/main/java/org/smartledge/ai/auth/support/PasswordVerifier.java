package org.smartledge.ai.auth.support;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 口令校验与编码（BCrypt，唯一实现）。
 *
 * <p>契约是**等工作量**：无论账号是否存在、口令哈希是否存在、口令是否为空，
 * 都要执行一次完整的 BCrypt 比较。否则"账号不存在"会比"口令错误"快一个数量级，
 * 攻击者据此可以枚举账号（登录接口的时间侧信道）。</p>
 *
 * <p>库里存的哈希是标准 BCrypt 文本（{@code $2a$/$2b$/$2y$}），由 Spring Security 的
 * {@link BCryptPasswordEncoder} 统一处理，因此不存在"自研比较逻辑"这条旁路。</p>
 */
@Component
public class PasswordVerifier {

    /**
     * 账号不存在时用来"陪跑"的哈希：值本身无意义，只要求它是合法的 BCrypt 文本
     * （cost 与真实哈希一致），使比较耗时与真实校验相同。
     */
    private static final String ABSENT_ACCOUNT_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private static final int BCRYPT_COST = 10;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST);

    /**
     * 校验明文口令。
     *
     * @param rawPassword  客户端提交的口令
     * @param passwordHash 库中哈希；{@code null}/空表示账号或哈希不存在
     * @return 仅在哈希存在且匹配时为 {@code true}
     */
    public boolean matches(String rawPassword, String passwordHash) {
        String candidate = rawPassword == null ? "" : rawPassword;
        boolean hashPresent = passwordHash != null && !passwordHash.isBlank();
        boolean matched = encoder.matches(candidate, hashPresent ? passwordHash : ABSENT_ACCOUNT_HASH);
        return hashPresent && matched;
    }

    /** 生成哈希；种子数据与后续改密都走这里，避免出现第二种哈希格式。 */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword == null ? "" : rawPassword);
    }
}
