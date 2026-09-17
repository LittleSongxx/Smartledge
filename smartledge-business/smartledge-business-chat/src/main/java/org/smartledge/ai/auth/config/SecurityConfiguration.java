package org.smartledge.ai.auth.config;

import jakarta.servlet.DispatcherType;
import org.smartledge.ai.auth.support.JwtAuthenticationFilter;
import org.smartledge.ai.auth.support.RestAccessDeniedHandler;
import org.smartledge.ai.auth.support.RestAuthenticationEntryPoint;
import org.smartledge.ai.auth.support.SecurityAuthorities;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 认证与授权的唯一入口配置。
 *
 * <p>规则是**白名单 + 默认拒绝**：只有登录接口、健康检查与错误页放行，其余全部需要已认证主体，
 * 未列出的路径一律拒绝（{@code anyRequest().denyAll()}）。这样"新增了一个忘记保护的接口"
 * 的症状是 403，而不是悄悄对全网开放。</p>
 *
 * <p>两端隔离靠 token 用途：{@code /manage/**} 与 {@code /admin/auth/**} 要求管理端用途，
 * 因此用户端 token 无法管理接口；{@code /api/chat/**} 要求 {@code chat:use}；
 * {@code /api/auth/me} 接受任意已认证主体。接口级权限判定在
 * {@code ManagePermissionInterceptor} 中按方法注解执行。</p>
 *
 * <p>无状态：没有 session、没有表单登录、没有 CSRF token（纯 API + Bearer token，
 * 不存在浏览器自动携带凭证的场景）。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(registry -> registry
                // 异步分发与错误分发不是独立入口：它们只发生在**同一请求**已经通过授权之后，
                // 而且此时响应通常已提交（SSE 流），继续按 URL 规则判定只会产生
                // "AuthorizationDeniedException: Access Denied + response already committed" 的
                // 日志噪音与错误的终态语义。放行它们不影响任何入口的鉴权。
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                // 预检请求不带凭证，放行不影响业务入口的鉴权（CORS 目前由前端 dev server 同源代理规避）。
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/admin/auth/login", "/api/auth/login").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/manage/**").hasAuthority(SecurityAuthorities.AUDIENCE_ADMIN)
                .requestMatchers("/admin/auth/**").hasAuthority(SecurityAuthorities.AUDIENCE_ADMIN)
                .requestMatchers("/actuator/**").hasAuthority(SecurityAuthorities.AUDIENCE_ADMIN)
                .requestMatchers("/api/auth/me").authenticated()
                .requestMatchers("/api/chat/**").hasAuthority(SecurityAuthorities.permission("chat:use"))
                .anyRequest().denyAll())
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 只保留 security filter chain 内的那一次 JWT 解析。
     *
     * <p>Spring Boot 会把容器里的 {@code Filter} Bean 自动注册到 Servlet 过滤器链上，
     * 那份注册在 security 之后执行，等于同一个 token 被解析两次；更糟的是它会让
     * "认证发生在授权之后"这件事看起来像有意设计。因此显式关闭自动注册。</p>
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
        JwtAuthenticationFilter jwtAuthenticationFilter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
            new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }
}
