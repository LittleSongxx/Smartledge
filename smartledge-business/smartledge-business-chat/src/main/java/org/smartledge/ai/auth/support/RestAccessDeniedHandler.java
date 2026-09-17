package org.smartledge.ai.auth.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.smartledge.common.ApiResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 已认证但无权限的 403 响应。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonResponseWriter jsonResponseWriter;

    public RestAccessDeniedHandler(JsonResponseWriter jsonResponseWriter) {
        this.jsonResponseWriter = jsonResponseWriter;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        jsonResponseWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
            ApiResponse.error(403, "当前账号没有该操作的权限"));
    }
}
