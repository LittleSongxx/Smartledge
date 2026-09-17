package org.smartledge.ai.auth.support;

import org.smartledge.exception.SuperAgentFrameException;

/**
 * 认证/授权拒绝：带真实 HTTP 语义的业务异常。
 *
 * <p>为什么需要单独的类型：本项目的全局异常 advice 不在应用的扫描范围内
 * （{@code org.smartledge.exception.DefaultExceptionHandler} 位于 {@code org.smartledge} 包下，
 * 而应用扫描 {@code org.smartledge.ai}），因此业务异常会以 HTTP 500 的形式返回给客户端。
 * 对"未登录 / 无权限 / 账号锁定"这类判定，500 是错误语义：它既让前端无法按 401/403 处理，
 * 也会把授权拒绝混进服务端错误告警。</p>
 *
 * <p>本类型只用于认证授权失败，由 {@link org.smartledge.ai.auth.config.AuthFailureExceptionHandler}
 * 映射成 401/403/423 + {@code ApiResponse}；其余业务异常保持原有行为不变（B3 不改变既有错误形状）。</p>
 */
public class AuthFailureException extends SuperAgentFrameException {

    public AuthFailureException(int code, String message) {
        super(code, message);
    }
}
