package org.smartledge.ai.auth.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.smartledge.ai.auth.config.PreviewModeProperties;
import org.smartledge.ai.chatagent.support.StreamEventWriter;
import org.smartledge.common.ApiResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 线上只读展示模式拦截器。
 *
 * <p>管理接口和业务聊天接口都采用默认拒绝策略：只有当前确认的只读接口
 * 才会放行。这样新增的写接口不会因为遗漏黑名单而在预览环境中开放。</p>
 */
@Component
public class PreviewModeInterceptor implements HandlerInterceptor {

    private static final Set<String> PREVIEW_READ_ONLY_PATHS = Set.of(
        "/api/chat/document/options",
        "/api/chat/knowledge-base/options",
        "/api/chat/session/detail",
        "/api/chat/exchange/detail",
        "/api/chat/session/list",
        "/api/chat/exchange/retrieval/results",
        "/api/chat/exchange/channel/executions",
        "/api/chat/stage/benchmarks",
        "/manage/document/page/query",
        "/manage/document/detail/query",
        "/manage/document/strategy/plan/query",
        "/manage/document/index/build/progress/query",
        "/manage/document/parse-route/progress/query",
        "/manage/document/parse-artifact/query",
        "/manage/document/parse-artifact/content/query",
        "/manage/document/parse-artifact/download",
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
        "/manage/knowledge/route/trace/page/query",
        "/manage/knowledge/base/list",
        "/manage/knowledge/base/detail",
        "/manage/config/current/query",
        "/manage/config/history/page/query",
        "/manage/config/history/detail/query",
        "/manage/observability/quality/overview/query",
        "/manage/evaluation/exchange/snapshot/query"
    );

    private final PreviewModeProperties previewModeProperties;

    private final ObjectMapper objectMapper;

    private final StreamEventWriter streamEventWriter;

    public PreviewModeInterceptor(PreviewModeProperties previewModeProperties,
                                  ObjectMapper objectMapper,
                                  StreamEventWriter streamEventWriter) {
        this.previewModeProperties = previewModeProperties;
        this.objectMapper = objectMapper;
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!Boolean.TRUE.equals(previewModeProperties.getEnabled())) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!isPreviewBlockedPath(path)) {
            return true;
        }

        if ("/api/chat/stream".equals(path)) {
            writeStreamReject(response);
        } else {
            writeJsonReject(response);
        }
        return false;
    }

    private boolean isPreviewBlockedPath(String path) {
        if (PREVIEW_READ_ONLY_PATHS.contains(path)) {
            return false;
        }
        return path.startsWith("/manage/") || path.startsWith("/api/chat/");
    }

    private void writeStreamReject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.getWriter().write("data: " + streamEventWriter.error(previewModeProperties.getMessage()) + "\n\n");
        response.getWriter().flush();
    }

    private void writeJsonReject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(previewModeProperties.getMessage())));
    }
}
