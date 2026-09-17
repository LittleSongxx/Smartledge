package org.smartledge.ai.auth.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioDemoInterceptorTest {

    private final PortfolioDemoInterceptor interceptor = new PortfolioDemoInterceptor(new ObjectMapper());

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("非试用账号不受拦截，写接口也放行给后续权限判定")
    void nonDemoIsNotBlocked() throws Exception {
        authenticate("admin", Set.of("document:delete"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/manage/document/delete"), response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("试用账号可以走确认过的只读查询")
    void demoMayCallAllowlistedRead() throws Exception {
        authenticate("reviewer", Set.of(PortfolioPermissions.DEMO, "document:read"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/manage/document/page/query"), response, new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/api/chat/stream"), response, new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/manage/config/current/query"), response, new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/manage/tenant/member/page/query"), response, new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/manage/knowledge/route/trace/page/query"), response, new Object())).isTrue();
    }

    @Test
    @DisplayName("试用账号访问未列入白名单的写接口时 403，且不放行")
    void demoWriteIsRejected() throws Exception {
        authenticate("reviewer", Set.of(PortfolioPermissions.DEMO, "document:read", "observe:read"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/manage/document/delete"), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains(PortfolioPermissions.WRITE_BLOCKED_MESSAGE);
    }

    @Test
    @DisplayName("试用账号不能执行写操作或读取配置历史原文")
    void demoCannotReachWrites() throws Exception {
        authenticate("reviewer", Set.of(PortfolioPermissions.DEMO, "observe:read", "config:read", "user:manage"));

        assertRejected("/manage/observability/session/summary/rebuild");
        assertRejected("/api/chat/session/summary/rebuild");
        assertRejected("/manage/document/upload");
        assertRejected("/manage/document/delete");
        assertRejected("/manage/config/item/update");
        assertRejected("/manage/config/history/detail/query");
        assertRejected("/manage/tenant/member/save");
        assertRejected("/manage/document/acl/grant");
    }

    private void assertRejected(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request(path), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    private void authenticate(String username, Set<String> permissions) {
        AuthenticatedPrincipal principal =
            new AuthenticatedPrincipal(1L, 201L, username, TokenAudience.ADMIN, Set.of(101L), permissions);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private HttpServletRequest request(String path) {
        return new MockHttpServletRequest("POST", path);
    }
}
