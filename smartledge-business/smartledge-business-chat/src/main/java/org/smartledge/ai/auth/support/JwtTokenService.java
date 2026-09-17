package org.smartledge.ai.auth.support;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.smartledge.ai.auth.config.AdminAuthProperties;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 登录 token 的签发与解析（HS256，密钥来自系统配置 {@code adminAuth.tokenSecret}）。
 *
 * <p>token 携带的身份是**登录时刻的快照**：{@code sub}(登录名)、{@code uid}(用户 id)、
 * {@code tid}(租户 id)、{@code aud}(用途)、{@code roles}(角色 id)、{@code perms}(权限编码)。
 * 这样每个请求都不需要回查用户/角色/权限表，代价是改权限要重新登录才生效 ——
 * 这是刻意的取舍：权限判定不能依赖"每次请求都查库成功"，否则数据库抖动会变成放行。</p>
 *
 * <p>角色的载荷是**角色 id** 而不是编码：文档 ACL 的主体是 {@code principal_id}（角色 id），
 * 用编码会让每次 ACL 解析都要多做一次编码到 id 的映射，而映射失配的后果是静默少授权。</p>
 */
@Component
public class JwtTokenService {

    public static final String CLAIM_USER_ID = "uid";

    public static final String CLAIM_TENANT_ID = "tid";

    public static final String CLAIM_ROLES = "roles";

    public static final String CLAIM_PERMISSIONS = "perms";

    private final AdminAuthProperties adminAuthProperties;

    public JwtTokenService(AdminAuthProperties adminAuthProperties) {
        this.adminAuthProperties = adminAuthProperties;
    }

    /** 按主体的实际用途签发 token。 */
    public String generateToken(AuthenticatedPrincipal principal) {
        Instant now = Instant.now();
        Instant expireAt = now.plusSeconds(adminAuthProperties.getTokenExpireMinutes() * 60);
        return Jwts.builder()
            .setSubject(principal.username())
            .claim(CLAIM_USER_ID, principal.userId())
            .claim(CLAIM_TENANT_ID, principal.tenantId())
            .claim(CLAIM_ROLES, List.copyOf(principal.roleIds()))
            .claim(CLAIM_PERMISSIONS, List.copyOf(principal.permissions()))
            .setAudience(principal.audience().claimValue())
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(expireAt))
            .signWith(
                SignatureAlgorithm.HS256,
                adminAuthProperties.getTokenSecret().getBytes(StandardCharsets.UTF_8)
            )
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
        TokenAudience audience = TokenAudience.fromClaim(claims.getAudience());
        if (username == null || username.isBlank() || userId == null || tenantId == null || audience == null) {
            throw new SuperAgentFrameException(401, "登录凭证缺少必要身份信息，请重新登录");
        }
        return new AuthenticatedPrincipal(
            tenantId,
            userId,
            username,
            audience,
            numberSetClaim(claims, CLAIM_ROLES),
            stringSetClaim(claims, CLAIM_PERMISSIONS)
        );
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                .setSigningKey(adminAuthProperties.getTokenSecret().getBytes(StandardCharsets.UTF_8))
                .parseClaimsJws(token)
                .getBody();
        }
        catch (ExpiredJwtException exception) {
            throw new SuperAgentFrameException(401, "登录已过期，请重新登录", exception);
        }
        catch (JwtException | IllegalArgumentException exception) {
            throw new SuperAgentFrameException(401, "登录凭证无效，请重新登录", exception);
        }
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
