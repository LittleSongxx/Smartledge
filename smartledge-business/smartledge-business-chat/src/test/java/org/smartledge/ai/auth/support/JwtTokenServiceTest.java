package org.smartledge.ai.auth.support;

import org.smartledge.ai.auth.config.AdminAuthProperties;
import org.smartledge.exception.SuperAgentFrameException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * token 签发与解析的不变量测试。
 *
 * <p>对应 B3 要求「JWT 带 tenant_id + user_id + 角色」以及两端隔离：用途（aud）写进 token，
 * 解析后必须还原；任何声明缺失、签名不符或过期的 token 都必须整体失败，不能返回"半可信"主体。</p>
 */
class JwtTokenServiceTest {

    private static final String SECRET = "unit-test-secret-please-change";

    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        jwtTokenService = new JwtTokenService(properties(SECRET, 720L));
    }

    @Test
    @DisplayName("往返后租户、用户、角色与权限完全一致")
    void roundTripKeepsIdentity() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            2L, 42L, "bob", TokenAudience.ADMIN, Set.of(4L, 5L), Set.of("console:access", "chat:use"));

        AuthenticatedPrincipal parsed = jwtTokenService.parseToken(jwtTokenService.generateToken(principal));

        assertThat(parsed.tenantId()).isEqualTo(2L);
        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.username()).isEqualTo("bob");
        assertThat(parsed.audience()).isEqualTo(TokenAudience.ADMIN);
        assertThat(parsed.roleIds()).containsExactlyInAnyOrder(4L, 5L);
        assertThat(parsed.permissions()).containsExactlyInAnyOrder("console:access", "chat:use");
    }

    @Test
    @DisplayName("两种用途互不混淆，解析后与签发时一致")
    void audienceIsPreserved() {
        AuthenticatedPrincipal chat = principal(TokenAudience.CHAT);
        AuthenticatedPrincipal admin = principal(TokenAudience.ADMIN);
        assertThat(jwtTokenService.parseToken(jwtTokenService.generateToken(chat)).audience())
            .isEqualTo(TokenAudience.CHAT);
        assertThat(jwtTokenService.parseToken(jwtTokenService.generateToken(admin)).audience())
            .isEqualTo(TokenAudience.ADMIN);
    }

    @Test
    @DisplayName("换密钥后旧 token 失效（轮换密钥即失效全部会话）")
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = jwtTokenService.generateToken(principal(TokenAudience.ADMIN));
        JwtTokenService rotated = new JwtTokenService(properties("another-secret", 720L));
        assertThatThrownBy(() -> rotated.parseToken(token))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("登录凭证无效");
    }

    @Test
    @DisplayName("结构被篡改的 token 解析失败，不返回部分身份")
    void tamperedTokenIsRejected() {
        String token = jwtTokenService.generateToken(principal(TokenAudience.ADMIN));
        String tampered = token.substring(0, token.lastIndexOf('.')) + ".AAAA";
        assertThatThrownBy(() -> jwtTokenService.parseToken(tampered))
            .isInstanceOf(SuperAgentFrameException.class);
        assertThatThrownBy(() -> jwtTokenService.parseToken("not-a-jwt"))
            .isInstanceOf(SuperAgentFrameException.class);
    }

    @Test
    @DisplayName("有效期按配置计算，回执与配置一致")
    void expireMinutesFollowConfiguration() {
        JwtTokenService shortLived = new JwtTokenService(properties(SECRET, 30L));
        assertThat(shortLived.tokenExpireMinutes()).isEqualTo(30L);
    }

    private AuthenticatedPrincipal principal(TokenAudience audience) {
        return new AuthenticatedPrincipal(1L, 1L, "admin", audience, Set.of(1L), Set.of("console:access"));
    }

    private AdminAuthProperties properties(String secret, long minutes) {
        AdminAuthProperties properties = new AdminAuthProperties();
        properties.setTokenSecret(secret);
        properties.setTokenExpireMinutes(minutes);
        return properties;
    }
}
