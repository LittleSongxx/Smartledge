package org.smartledge.ai.auth.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 当前请求的认证主体（唯一读取入口）。
 *
 * <p>主体来自 Spring Security 的 {@code SecurityContext}，由 {@link JwtAuthenticationFilter}
 * 在请求进入时写入。业务代码不解析 token，也不从请求头取身份。</p>
 */
public final class AdminRequestContext {

    private AdminRequestContext() {
    }

    /** 当前认证主体；未认证时为空。 */
    public static Optional<AuthenticatedPrincipal> currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedPrincipal authenticatedPrincipal) {
            return Optional.of(authenticatedPrincipal);
        }
        return Optional.empty();
    }

    /** 当前登录名；未认证时返回空串（调用方按"无身份"处理）。 */
    public static String resolveUsername() {
        return currentPrincipal().map(AuthenticatedPrincipal::username).orElse("");
    }

    /**
     * 保留带请求参数的读取方式：审计与限流代码拿到的是请求对象，不应因此多一次转换。
     */
    public static String resolveUsername(HttpServletRequest request) {
        return resolveUsername();
    }
}
