package org.smartledge.ai.auth.support;

import org.smartledge.ai.auth.service.AuthAccountStore;
import org.springframework.stereotype.Component;

/**
 * 解析 JWT 后回查账号/租户启用状态与 token 版本。
 *
 * <p>签名通过只证明"曾经签发过"，不能证明"现在还有效"。停用、改角色、重置身份或登出
 * 都会升高 {@code token_version}，旧 token 在这里被拒绝。</p>
 */
@Component
public class AuthSessionValidator {

    private final AuthAccountStore authAccountStore;

    public AuthSessionValidator(AuthAccountStore authAccountStore) {
        this.authAccountStore = authAccountStore;
    }

    public void validate(AuthenticatedPrincipal principal) {
        if (principal == null) {
            throw new AuthFailureException(401, "请先登录");
        }
        AuthAccountStore.SessionAccount session = authAccountStore
            .findSessionAccount(principal.tenantId(), principal.userId())
            .orElseThrow(() -> new AuthFailureException(401, "登录凭证已失效，请重新登录"));
        if (!session.userEnabled() || !session.tenantEnabled()
            || session.tokenVersion() != principal.tokenVersion()) {
            throw new AuthFailureException(401, "登录凭证已失效，请重新登录");
        }
    }
}
