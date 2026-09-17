package org.smartledge.ai.auth.config;

import org.smartledge.ai.auth.support.AuthFailureException;
import org.smartledge.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 认证/授权失败的响应映射：把 {@link AuthFailureException} 的 code 变成真实的 HTTP 状态。
 *
 * <p>与 security filter 层的响应形状保持一致（{@code ApiResponse.error(code, message)}），
 * 因此前端只需要一套处理逻辑：401 清 token 跳登录、403 提示无权限。</p>
 *
 * <p>只处理认证授权异常：其它业务异常的形状与本阶段无关，不在 B3 内改动。</p>
 */
@RestControllerAdvice
public class AuthFailureExceptionHandler {

    @ExceptionHandler(AuthFailureException.class)
    public ResponseEntity<ApiResponse<String>> handle(AuthFailureException exception) {
        HttpStatus status = switch (exception.getCode()) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 423 -> HttpStatus.LOCKED;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
            .body(ApiResponse.error(exception.getCode(), exception.getMessage()));
    }
}
