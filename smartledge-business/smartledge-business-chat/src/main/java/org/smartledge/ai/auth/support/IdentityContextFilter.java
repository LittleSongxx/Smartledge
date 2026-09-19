package org.smartledge.ai.auth.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为 Web 请求建立租户与身份上下文。
 *
 * <p>租户拦截器是 fail-closed 的：拿不到上下文就拒绝构造查询。因此所有访问业务表的
 * 入口都必须先建立上下文 —— Web 请求走本过滤器，后台线程（消息消费、索引构建、
 * 对账任务）显式声明系统上下文。</p>
 *
 * <p>上下文只有一个来源：**已认证主体**。{@link TenantContext#setIdentity(RequestIdentity)}
 * 同时写入租户与主体，两者不可能来自不同请求。没有认证主体的请求（登录、健康检查）
 * 不带任何租户上下文，因此任何试图访问业务表的代码都会 fail closed ——
 * 这里不存在"默认租户"回退。</p>
 *
 * <p>本过滤器由 Servlet 容器注册（默认低优先级），执行顺序在 spring security 过滤器链之后，
 * 因此读到的 {@code SecurityContext} 已经是 {@link JwtAuthenticationFilter} 填好的。</p>
 */
@Slf4j
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            AdminRequestContext.currentPrincipal().ifPresent(principal -> {
                RequestIdentity identity = principal.toRequestIdentity();
                TenantContext.setIdentity(identity);
            });
            filterChain.doFilter(request, response);
        }
        finally {
            TenantContext.clear();
        }
    }
}
