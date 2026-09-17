package org.smartledge.ai.auth.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.smartledge.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证请求的 401 响应（与旧管理端拦截器的响应形状一致，前端据此清理 token 并跳登录页）。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonResponseWriter jsonResponseWriter;

    public RestAuthenticationEntryPoint(JsonResponseWriter jsonResponseWriter) {
        this.jsonResponseWriter = jsonResponseWriter;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object invalidTokenMessage = request.getAttribute(JwtAuthenticationFilter.INVALID_TOKEN_ATTRIBUTE);
        String message = invalidTokenMessage == null
            ? "请先登录"
            : String.valueOf(invalidTokenMessage);
        jsonResponseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, ApiResponse.error(401, message));
    }
}
