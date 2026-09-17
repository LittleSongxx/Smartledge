package org.smartledge.ai.auth.support;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理接口权限判定的不变量测试。
 *
 * <p>对应 B3 的退出条件「{@code /manage/**} 需要管理权限」与"无任何无鉴权业务入口"：
 * 判定必须**默认拒绝**（未声明权限的处理方法一律 403），并且失败时不得继续进入控制器。</p>
 */
class ManagePermissionInterceptorTest {

    private final JsonResponseWriter jsonResponseWriter =
        new JsonResponseWriter(new com.fasterxml.jackson.databind.ObjectMapper());

    private final ManagePermissionInterceptor interceptor = new ManagePermissionInterceptor(jsonResponseWriter);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("持有要求的权限时放行")
    void passesWhenPermissionGranted() throws Exception {
        authenticate("operate", Set.of("document:read"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("POST"), response, handler("annotated"));

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("未声明权限的方法回退到类注解，按类注解判定")
    void fallsBackToClassAnnotation() throws Exception {
        authenticate("reader", Set.of("kb:read"));
        assertThat(interceptor.preHandle(request("POST"), new MockHttpServletResponse(), handler("classLevel")))
            .isTrue();
    }

    @Test
    @DisplayName("缺少要求的权限时返回 403 且不放行")
    void rejectsWhenPermissionMissing() throws Exception {
        authenticate("read-only", Set.of("observe:read"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("POST"), response, handler("annotated"));

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("没有该操作的权限");
    }

    @Test
    @DisplayName("方法注解覆盖类注解：只有方法声明的权限才是本接口的要求")
    void methodAnnotationWinsOverClassAnnotation() throws Exception {
        authenticate("admin", Set.of("kb:delete"));

        assertThat(interceptor.preHandle(request("POST"), new MockHttpServletResponse(), handler("methodLevel")))
            .isTrue();

        // 只有一个方法级权限时，类级权限不再补齐：持有类级权限但缺方法级权限必须被拒。
        authenticate("read-only", Set.of("kb:read"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("POST"), response, handler("methodLevel"))).isFalse();
        assertThat(response.getContentAsString()).contains("kb:delete");
    }

    @Test
    @DisplayName("类与方法都没有声明权限时默认拒绝（新增接口不会静默开放）")
    void undeclaredHandlerFailsClosed() throws Exception {
        authenticate("admin", Set.of("kb:delete", "console:access"));

        Method undeclared = UnannotatedManageController.class.getDeclaredMethod("undeclared");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("POST"), response,
            new HandlerMethod(new UnannotatedManageController(), undeclared))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("未声明权限要求");
    }

    @Test
    @DisplayName("没有认证主体时返回 401，不放行")
    void rejectsWithoutPrincipal() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("POST"), response, handler("annotated"));

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("非处理方法与预检请求交给 filter chain 处理，不在这里拒绝")
    void skipsNonHandlerAndPreflight() throws Exception {
        assertThat(interceptor.preHandle(request("POST"), new MockHttpServletResponse(), new Object())).isTrue();
        // 预检请求不带凭证，若在此处拒绝，浏览器预检会失败。
        assertThat(interceptor.preHandle(request("OPTIONS"), new MockHttpServletResponse(), handler("classLevel")))
            .isTrue();
    }

    @Test
    @DisplayName("未认证时读取主体返回空，认证后返回完整主体")
    void requestContextReadsSecurityContext() {
        assertThat(AdminRequestContext.currentPrincipal()).isEmpty();
        assertThat(AdminRequestContext.resolveUsername()).isEmpty();

        authenticate("alice", Set.of("chat:use"));

        Optional<AuthenticatedPrincipal> principal = AdminRequestContext.currentPrincipal();
        assertThat(principal).isPresent();
        assertThat(principal.get().username()).isEqualTo("alice");
        assertThat(principal.get().toRequestIdentity().tenantId()).isEqualTo(1L);
        assertThat(AdminRequestContext.resolveUsername(new MockHttpServletRequest())).isEqualTo("alice");
    }

    private void authenticate(String username, Set<String> permissions) {
        AuthenticatedPrincipal principal =
            new AuthenticatedPrincipal(1L, 9L, username, TokenAudience.ADMIN, Set.of(1L), permissions);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private HttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/manage/document/page/query");
        return request;
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method method = SampleManageController.class.getDeclaredMethod(methodName);
        return new HandlerMethod(new SampleManageController(), method);
    }

    /** 供拦截器读取注解的样例控制器。 */
    @RequiresPermission("kb:read")
    static class SampleManageController {

        @RequiresPermission("document:read")
        public void annotated() {
        }

        @RequiresPermission("kb:delete")
        public void methodLevel() {
        }

        public void classLevel() {
        }
    }

    /** 完全没有权限声明的控制器：用于验证默认拒绝。 */
    static class UnannotatedManageController {

        public void undeclared() {
        }
    }
}
