package org.smartledge.ai.auth.support;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.smartledge.ai.auth.config.AdminAuthProperties;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 登录 token 的签发与解析（HS256，密钥来自本地/环境配置 {@code app.admin-auth.token-secret}）。
 *
 * <p>token 携带登录时刻的身份快照，并带 {@code ver}（token 版本）与 {@code jti}。
 * 过滤器在验签之后还会回查账号/租户启用状态与版本，因此停用、改角色或登出后旧 token 立即失效。</p>
 */
@Component
public class JwtTokenService {

    public static final String CLAIM_USER_ID = "uid";

    public static final String CLAIM_TENANT_ID = "tid";

    public static final String CLAIM_ROLES = "roles";

    public static final String CLAIM_PERMISSIONS = "perms";

    public static final String CLAIM_TOKEN_VERSION = "ver";

    private final AdminAuthProperties adminAuthProperties;

    public JwtTokenService(AdminAuthProperties adminAuthProperties) {
        this.adminAuthProperties = adminAuthProperties;
    }

    /** 按主体的实际用途签发 token。 */
    public String generateToken(AuthenticatedPrincipal principal) {
        Instant now = Instant.now();
        Instant expireAt = now.plusSeconds(adminAuthProperties.getTokenExpireMinutes() * 60);
        String jti = principal.jti() == null || principal.jti().isBlank()
            ? UUID.randomUUID().toString()
            : principal.jti();
        return Jwts.builder()
            .id(jti)
            .subject(principal.username())
            .claim(CLAIM_USER_ID, principal.userId())
            .claim(CLAIM_TENANT_ID, principal.tenantId())
            .claim(CLAIM_ROLES, List.copyOf(principal.roleIds()))
            .claim(CLAIM_PERMISSIONS, List.copyOf(principal.permissions()))
            .claim(CLAIM_TOKEN_VERSION, principal.tokenVersion())
            .audience().add(principal.audience().claimValue()).and()
            .issuedAt(Date.from(now))
            .expiration(Date.from(expireAt))
            .signWith(signingKey(), Jwts.SIG.HS256)
            .compact();
    }

    /** token 有效期（分钟），用于登录响应回执。 */
    public long tokenExpireMinutes() {
        return adminAuthProperties.getTokenExpireMinutes();
    }

    /**
     * 解析并校验 token。
     *
     * <p>任何不合法（签名错误、过期、缺少必要声明、用途未知）都抛 401，
     * 不返回"半可信"的主体：调用方拿到返回值就代表身份完整。</p>
     */
    public AuthenticatedPrincipal parseToken(String token) {
        Claims claims = parseClaims(token);
        String username = claims.getSubject();
        Long userId = numberClaim(claims, CLAIM_USER_ID);
        Long tenantId = numberClaim(claims, CLAIM_TENANT_ID);
        TokenAudience audience = TokenAudience.fromClaim(firstAudience(claims));
        if (username == null || username.isBlank() || userId == null || tenantId == null || audience == null) {
            throw new SuperAgentFrameException(401, "登录凭证缺少必要身份信息，请重新登录");
        }
        Long version = numberClaim(claims, CLAIM_TOKEN_VERSION);
        return new AuthenticatedPrincipal(
            tenantId,
            userId,
            username,
            audience,
            numberSetClaim(claims, CLAIM_ROLES),
            stringSetClaim(claims, CLAIM_PERMISSIONS),
            version == null ? 1L : version,
            claims.getId()
        );
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        }
        catch (ExpiredJwtException exception) {
            throw new SuperAgentFrameException(401, "登录已过期，请重新登录", exception);
        }
        catch (JwtException | IllegalArgumentException exception) {
            throw new SuperAgentFrameException(401, "登录凭证无效，请重新登录", exception);
        }
    }

    private SecretKey signingKey() {
        byte[] secret = adminAuthProperties.getTokenSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            try {
                secret = MessageDigest.getInstance("SHA-256").digest(secret);
            }
            catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 required to lengthen short JWT secrets", exception);
            }
        }
        return Keys.hmacShaKeyFor(secret);
    }

    private static String firstAudience(Claims claims) {
        Set<String> audiences = claims.getAudience();
        if (audiences == null || audiences.isEmpty()) {
            return null;
        }
        return audiences.iterator().next();
    }

    private Long numberClaim(Claims claims, String name) {
        Object raw = claims.get(name);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text.trim());
            }
            catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private Set<Long> numberSetClaim(Claims claims, String name) {
        Object raw = claims.get(name);
        if (!(raw instanceof List<?> list)) {
            return Set.of();
        }
        Set<Long> values = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof Number number) {
                values.add(number.longValue());
            }
        }
        return Set.copyOf(values);
    }

    private Set<String> stringSetClaim(Claims claims, String name) {
        Object raw = claims.get(name);
        if (!(raw instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item));
            }
        }
        return Set.copyOf(values);
    }
}
