package org.smartledge.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.smartledge.common.ApiResponse;
import org.smartledge.enums.BaseCode;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务校验失败（{@link SuperAgentFrameException}）的响应映射。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>`smartledge-common-frame` 里已经有一个 `DefaultExceptionHandler`，但它位于
 * `org.smartledge.exception`，而本应用的组件扫描根是 `org.smartledge.ai` —— 它从来没有被注册过。
 * 结果是**任何业务校验失败都退化成 `HTTP 500 + {"status":500,"error":"Internal Server Error"}`**：
 * "口令太短""登录名已被使用""角色不属于当前租户"这些原因全部丢失，界面只能显示"操作失败"。
 * 这不是新引入的缺陷（S23 开工实测：既有的 `/manage/knowledge/base/detail` 传不存在的 id 同样 500），
 * 但 S23 把管理动作交给租户管理员之后，不可读的拒绝理由直接变成产品缺陷。</p>
 *
 * <h2>形状</h2>
 *
 * <p>与 {@code AuthFailureExceptionHandler} 对认证授权异常的处置一致：**4xx + 平台统一的
 * {@code ApiResponse} 信封**。前端因此只需要一套错误处理（读 {@code message}），
 * 不必区分"500 的通用页"和"400 的业务提示"。</p>
 *
 * <p>状态码按语义分档：参数类（{@code BaseCode.PARAMETER_ERROR}）→ 400。
 * 其它业务码保持 400 而不是 500：能走到这里的都是"请求本身不成立"，不是服务端故障。</p>
 */
@Slf4j
@RestControllerAdvice
public class BusinessExceptionHandler {

    @ExceptionHandler(SuperAgentFrameException.class)
    public ResponseEntity<ApiResponse<String>> handle(SuperAgentFrameException exception) {
        log.warn("业务请求被拒绝：code={}, message={}", exception.getCode(), exception.getMessage());
        Integer code = exception.getCode();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(code == null ? BaseCode.PARAMETER_ERROR.getCode() : code, exception.getMessage()));
    }
}
