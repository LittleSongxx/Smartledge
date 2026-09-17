package org.smartledge.ai.auth.support;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.smartledge.common.ApiResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * 管理接口的权限判定（{@code /manage/**}）。
 *
 * <p>与 filter chain 的分工：filter chain 只判定"这是不是管理端的 token"，
 * 本拦截器判定"这个 token 有没有这个接口要的权限"。放在拦截器而不是 filter 里，
 * 是因为它要读方法/类上的 {@link RequiresPermission}。</p>
 *
 * <p><b>默认拒绝</b>：{@code /manage/**} 上任何没有声明权限的处理方法都返回 403。
 * 这是刻意的 —— 漏标注解的后果必须是"接口不可用"，而不是"接口对全部管理角色开放"。</p>
 */
@Component
public class ManagePermissionInterceptor implements HandlerInterceptor {

    private final JsonResponseWriter jsonResponseWriter;

    public ManagePermissionInterceptor(JsonResponseWriter jsonResponseWriter) {
        this.jsonResponseWriter = jsonResponseWriter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 异步分发与错误分发不是独立入口：它们只发生在同一请求首次分发已经通过本拦截器之后，
        // 而此时过滤器链上已没有认证主体，再判定一次只会得到假 401。与 filter chain 的
        // dispatcherTypeMatchers 保持同一口径。
        DispatcherType dispatcherType = request.getDispatcherType();
        if (DispatcherType.ASYNC.equals(dispatcherType) || DispatcherType.ERROR.equals(dispatcherType)) {
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // 静态资源、错误页等没有权限声明可读，交给 filter chain 的规则处理。
            return true;
        }
        Optional<String> requiredPermission = requiredPermission(handlerMethod);
        if (requiredPermission.isEmpty()) {
            jsonResponseWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                ApiResponse.error(403, "该管理接口未声明权限要求，已拒绝访问"));
            return false;
        }
        AuthenticatedPrincipal principal = AdminRequestContext.currentPrincipal().orElse(null);
        if (principal == null) {
            jsonResponseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                ApiResponse.error(401, "请先登录"));
            return false;
        }
        if (!principal.hasPermission(requiredPermission.get())) {
            jsonResponseWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                ApiResponse.error(403, "当前账号没有该操作的权限：" + requiredPermission.get()));
            return false;
        }
        return true;
    }

    /** 方法注解优先于类注解；都没有表示未声明。 */
    private Optional<String> requiredPermission(HandlerMethod handlerMethod) {
        RequiresPermission methodLevel = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        RequiresPermission resolved = methodLevel == null
            ? handlerMethod.getBeanType().getAnnotation(RequiresPermission.class)
            : methodLevel;
        return resolved == null ? Optional.empty() : Optional.of(resolved.value());
    }
}
