package org.smartledge.ai.auth.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求上下文过滤器的不变量测试。
 *
 * <p>对应 B3 的"删除默认租户回退"：改造前 {@code resolveTenantId} 无条件返回默认租户 1，
 * 于是任何未认证请求都以租户 1 的身份访问业务表。下面的用例在改造前必然是红的
 * （过滤器内能读到租户 1），改造后必须读到"没有上下文"。</p>
 */
class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("已认证请求：租户与身份都来自认证主体")
    void authenticatedRequestCarriesTenantAndIdentity() throws Exception {
        RequestIdentity identity = new RequestIdentity(2L, 4L, "bob", Set.of(5L), Set.of("chat:use"));
        authenticate(new AuthenticatedPrincipal(2L, 4L, "bob", TokenAudience.CHAT,
            identity.roleIds(), identity.permissions()));
        AtomicReference<RequestIdentity> observed = new AtomicReference<>();

        filter.doFilter(request(), new MockHttpServletResponse(), capture(observed));

        assertThat(observed.get()).isEqualTo(identity);
        assertThat(observed.get().tenantId()).isEqualTo(2L);
        // 过滤器退出后不得把上下文留在请求线程上
        assertThat(TenantContext.get()).isNull();
        assertThat(TenantContext.getIdentity()).isNull();
    }

    @Test
    @DisplayName("未认证请求：不建立任何上下文，绝不回退到默认租户")
    void anonymousRequestHasNoTenantContext() throws Exception {
        AtomicReference<Long> observedTenant = new AtomicReference<>();
        AtomicReference<RequestIdentity> observedIdentity = new AtomicReference<>();

        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> {
            observedTenant.set(TenantContext.get());
            observedIdentity.set(TenantContext.getIdentity());
        });

        assertThat(observedTenant.get()).isNull();
        assertThat(observedIdentity.get()).isNull();
    }

    @Test
    @DisplayName("退出过滤器后清理上下文，不污染复用线程")
    void contextIsClearedAfterRequest() throws Exception {
        authenticate(new AuthenticatedPrincipal(1L, 1L, "admin", TokenAudience.ADMIN, Set.of(1L),
            Set.of("console:access")));

        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(TenantContext.isPresent()).isFalse();
    }

    private void authenticate(AuthenticatedPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private HttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/chat/session/list");
    }

    private FilterChain capture(AtomicReference<RequestIdentity> observed) {
        return (request, response) -> observed.set(TenantContext.getIdentity());
    }
}
