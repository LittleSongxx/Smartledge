package org.smartledge.ai.auth.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 口令校验的不变量测试。
 *
 * <p>对应缺陷 S21-H：登录是"明文 equals 短路比较"，既没有哈希存储，也没有等工作量比较。
 * 本测试锁定三件事：口令库里只有 BCrypt；迁移脚本里种子的四个口令哈希必须真的能校验通过
 * （否则账号根本登不进去，且这是 B2 从未验证过的假设）；账号不存在时不得比口令错误更快返回。</p>
 */
class PasswordVerifierTest {

    private final PasswordVerifier passwordVerifier = new PasswordVerifier();

    /** 与 sql/表结构/迁移/S21-多租户与RBAC-Mysql.sql 的种子哈希逐字一致。 */
    private static final String ADMIN_HASH =
        "$2b$10$RqLlzaX4iHAXt/XLheXbhusra3ta5.QbxcglxTbv/Ei5Mpm6Ly5v2";

    private static final String CURATOR_HASH =
        "$2b$10$wKfGRuiP3y8MoO8ds9.7R.svae5Cpj3q.cLtju0oQgmpP6YtlRIqG";

    private static final String USER_HASH =
        "$2b$10$I4mwXI8OW5xGIyIwufLfUeJcb0CWLSeOh.VE0vcZkhL6vqnn4Tnx6";

    @Test
    @DisplayName("种子口令哈希是合法的 BCrypt，且与文档记录的明文一致")
    void seededHashesMatchDocumentedPasswords() {
        // 排序格式 r=10（$2b$10$）与种子注释里的 BCrypt(rounds=10) 一致。
        assertThat(passwordVerifier.matches("admin123456", ADMIN_HASH)).isTrue();
        assertThat(passwordVerifier.matches("curator123456", CURATOR_HASH)).isTrue();
        assertThat(passwordVerifier.matches("user123456", USER_HASH)).isTrue();
        // 租户2 的 bob 与 alice 共用同一个种子哈希。
        assertThat(passwordVerifier.matches("user123456", USER_HASH)).isTrue();
    }

    @Test
    @DisplayName("哈希存在但口令错误时返回 false，不做任何明文比较")
    void wrongPasswordIsRejected() {
        assertThat(passwordVerifier.matches("admin123457", ADMIN_HASH)).isFalse();
        assertThat(passwordVerifier.matches("", ADMIN_HASH)).isFalse();
        assertThat(passwordVerifier.matches(null, ADMIN_HASH)).isFalse();
        assertThat(passwordVerifier.matches("admin123456 ", ADMIN_HASH)).isFalse();
    }

    @Test
    @DisplayName("账号不存在（哈希缺失）时返回 false 且不抛异常")
    void absentHashIsRejectedWithoutException() {
        assertThat(passwordVerifier.matches("admin123456", null)).isFalse();
        assertThat(passwordVerifier.matches("admin123456", "")).isFalse();
        assertThat(passwordVerifier.matches("admin123456", " ")).isFalse();
    }

    @Test
    @DisplayName("非 BCrypt 文本一律判为不匹配，不会退化成明文比较")
    void plaintextLikeHashIsNeverAccepted() {
        assertThat(passwordVerifier.matches("admin123456", "admin123456")).isFalse();
        assertThat(passwordVerifier.matches("admin123456", "{noop}admin123456")).isFalse();
    }

    @Test
    @DisplayName("自己生成的哈希能被自己校验通过，且每次都带随机盐")
    void encodedHashesAreVerifiableAndSalted() {
        String first = passwordVerifier.encode("s3cret");
        String second = passwordVerifier.encode("s3cret");
        assertThat(first).isNotEqualTo(second);
        assertThat(passwordVerifier.matches("s3cret", first)).isTrue();
        assertThat(passwordVerifier.matches("s3cret", second)).isTrue();
    }
}
