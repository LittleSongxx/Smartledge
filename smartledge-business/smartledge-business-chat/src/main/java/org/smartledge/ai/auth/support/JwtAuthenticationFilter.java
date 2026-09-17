package org.smartledge.ai.auth.support;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@code Authorization: Bearer <token>} 建立认证上下文。
 *
 * <p>无效或过期的 token **不建立认证**，而不是直接写响应：这样公开接口（登录）不会被一个
 * 残留的失效 token 拦住，而受保护接口仍然由授权规则拒绝（fail closed，绝不会因为
 * "token 解析失败"就放行）。失败原因记在请求属性里，供入口点返回更准确的文案。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 请求属性名：token 解析失败原因，供 {@link RestAuthenticationEntryPoint} 使用。 */
    public static final String INVALID_TOKEN_ATTRIBUTE = "smartledge.auth.invalidToken";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;

    private final ObjectProvider<AuthSessionValidator> authSessionValidator;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService,
                                   ObjectProvider<AuthSessionValidator> authSessionValidator) {
        this.jwtTokenService = jwtTokenService;
        this.authSessionValidator = authSessionValidator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request.getHeader(AUTHORIZATION_HEADER));
        if (StrUtil.isBlank(token)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            AuthenticatedPrincipal principal = jwtTokenService.parseToken(token);
            AuthSessionValidator validator = authSessionValidator == null ? null : authSessionValidator.getIfAvailable();
            if (validator != null) {
                validator.validate(principal);
            }
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities(principal));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        catch (SuperAgentFrameException exception) {
            SecurityContextHolder.clearContext();
            request.setAttribute(INVALID_TOKEN_ATTRIBUTE, exception.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    private List<GrantedAuthority> authorities(AuthenticatedPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(SecurityAuthorities.audience(principal.audience())));
        for (String permissionCode : principal.permissions()) {
            authorities.add(new SimpleGrantedAuthority(SecurityAuthorities.permission(permissionCode)));
        }
        return authorities;
    }

    private String resolveToken(String authorization) {
        if (StrUtil.isBlank(authorization)) {
            return null;
        }
        if (StrUtil.startWithIgnoreCase(authorization, BEARER_PREFIX)) {
            return StrUtil.trim(authorization.substring(BEARER_PREFIX.length()));
        }
        return null;
    }
}
