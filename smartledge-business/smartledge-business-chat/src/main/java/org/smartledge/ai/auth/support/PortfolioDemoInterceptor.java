package org.smartledge.ai.auth.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.smartledge.common.ApiResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 作品集试用账号的写操作默认拒绝。
 *
 * <p>只对持有 {@link PortfolioPermissions#DEMO} 的主体生效。白名单是当前确认的只读
 * （外加试用聊天必需的提问/停写）接口；新增 {@code /manage/**} 或 {@code /api/chat/**}
 * 接口默认对试用账号关闭，避免漏加黑名单。</p>
 */
@Component
public class PortfolioDemoInterceptor implements HandlerInterceptor {

    private static final Set<String> ALLOWED_PATHS = Set.of(
        "/api/chat/stream",
        "/api/chat/document/options",
        "/api/chat/knowledge-base/options",
        "/api/chat/session/detail",
        "/api/chat/exchange/detail",
        "/api/chat/session/list",
        "/api/chat/session/stop",
        "/api/chat/session/reset",
        "/api/chat/exchange/retrieval/results",
        "/api/chat/exchange/channel/executions",
        "/api/chat/exchange/knowledge-route/query",
        "/api/chat/stage/benchmarks",
        "/manage/document/page/query",
        "/manage/document/detail/query",
        "/manage/document/strategy/plan/query",
        "/manage/document/index/build/progress/query",
        "/manage/document/parse-route/progress/query",
        "/manage/document/parse-artifact/query",
        "/manage/document/parse-artifact/content/query",
        "/manage/document/parse-artifact/download",
        "/manage/document/acl/query",
        "/manage/document/acl/principal/list",
        "/manage/document/chunk/query",
        "/manage/document/chunk/detail/query",
        "/manage/document/rag/snapshot/query",
        "/manage/document/rag/parser-diagnostic/query",
        "/manage/document/rag/page-overlay/index/query",
        "/manage/document/rag/page-overlay/detail/query",
        "/manage/document/rag/artifact/node/page/query",
        "/manage/document/rag/artifact/node/detail/query",
        "/manage/document/rag/artifact/graph/window/query",
        "/manage/document/rag/artifact/relation/page/query",
        "/manage/document/rag/artifact/table/window/query",
        "/manage/document/task/log/query",
        "/manage/knowledge/scope/list",
        "/manage/knowledge/topic/list",
        "/manage/knowledge/document/profile/detail",
        "/manage/knowledge/topic/document/list",
        "/manage/knowledge/base/list",
        "/manage/knowledge/base/detail",
        "/manage/knowledge/route/trace/page/query",
        "/manage/tenant/member/page/query",
        "/manage/tenant/member/role/list",
        "/manage/config/current/query",
        "/manage/config/history/page/query",
        "/manage/observability/quality/overview/query",
        "/manage/observability/session/page/query",
        "/manage/observability/session/detail/query",
        "/manage/observability/exchange/detail/query",
        "/manage/observability/exchange/retrieval/results",
        "/manage/observability/exchange/channel/executions",
        "/manage/observability/stage/benchmarks"
    );

    private final ObjectMapper objectMapper;

    public PortfolioDemoInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        DispatcherType dispatcherType = request.getDispatcherType();
        if (DispatcherType.ASYNC.equals(dispatcherType) || DispatcherType.ERROR.equals(dispatcherType)) {
            return true;
        }
        AuthenticatedPrincipal principal = AdminRequestContext.currentPrincipal().orElse(null);
        if (!PortfolioPermissions.isDemo(principal)) {
            return true;
        }
        String path = normalizePath(request);
        if (!isGuardedPath(path) || ALLOWED_PATHS.contains(path)) {
            return true;
        }
        writeJsonReject(response);
        return false;
    }

    private static boolean isGuardedPath(String path) {
        return path.startsWith("/manage/") || path.startsWith("/api/chat/");
    }

    private static String normalizePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath == null || contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private void writeJsonReject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
            ApiResponse.error(403, PortfolioPermissions.WRITE_BLOCKED_MESSAGE)));
    }
}
