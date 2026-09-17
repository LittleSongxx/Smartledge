package org.smartledge.ai.auth.config;

import org.smartledge.ai.auth.support.AuthenticatedPrincipal;
import org.smartledge.ai.auth.support.JwtAuthenticationFilter;
import org.smartledge.ai.auth.support.JwtTokenService;
import org.smartledge.ai.auth.support.ManagePermissionInterceptor;
import org.smartledge.ai.auth.support.PortfolioDemoInterceptor;
import org.smartledge.ai.auth.support.PreviewModeInterceptor;
import org.smartledge.ai.auth.support.RequiresPermission;
import org.smartledge.ai.auth.support.RestAccessDeniedHandler;
import org.smartledge.ai.auth.support.RestAuthenticationEntryPoint;
import org.smartledge.ai.auth.support.JsonResponseWriter;
import org.smartledge.ai.auth.support.TokenAudience;
import org.smartledge.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import jakarta.servlet.DispatcherType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * filter chain 授权规则的不变量测试（真实 security 链 + 真实权限拦截器）。
 *
 * <p>对应 B3 退出条件：{@code /api/chat/**} 需要用户身份、{@code /manage/**} 需要管理权限、
 * 无任何无鉴权业务入口。这里用真实配置而不是复制一份规则表，因此断言的就是产品行为：
 * 用户端 token 进不了管理接口、管理端 token 缺权限进不去、未声明的管理接口一律拒绝、
 * 曾经被排除鉴权的评测快照接口不再例外。</p>
 */
@WebMvcTest(controllers = SecurityProbeController.class)
@Import({SecurityConfiguration.class,
    JwtAuthenticationFilter.class,
    JsonResponseWriter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    ManagePermissionInterceptor.class,
    AdminWebMvcConfiguration.class,
    SecurityFilterChainRulesTest.TokenSupport.class})
class SecurityFilterChainRulesTest {

    private static final String OBSERVE_PERMISSION = "observe:read";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private JwtTokenService jwtTokenService;

    /**
     * 预览模式只读闸门与鉴权无关（生产默认关闭），这里让它无条件放行，
     * 使被断言的对象只有 security 链与管理权限拦截器。
     */
    @MockitoBean
    private PreviewModeInterceptor previewModeInterceptor;

    @MockitoBean
    private PortfolioDemoInterceptor portfolioDemoInterceptor;

    private MockMvc mockMvc;

    private MockMvc mockMvc() throws Exception {
        if (mockMvc == null) {
            lenient().when(previewModeInterceptor.preHandle(any(), any(), any())).thenReturn(true);
            lenient().when(portfolioDemoInterceptor.preHandle(any(), any(), any())).thenReturn(true);
            mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        }
        return mockMvc;
    }

    @Test
    @DisplayName("对话接口无 token 时 401，必须持 chat:use")
    void chatRequiresChatUse() throws Exception {
        mockMvc().perform(post("/api/chat/probe"))
            .andExpect(status().isUnauthorized());

        mockMvc().perform(post("/api/chat/probe").header("Authorization", bearer(principal(TokenAudience.CHAT, Set.of("chat:use")))))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("chat-ok")));

        mockMvc().perform(post("/api/chat/probe").header("Authorization",
                bearer(principal(TokenAudience.ADMIN, Set.of(OBSERVE_PERMISSION)))))
            .andExpect(status().isForbidden());

        mockMvc().perform(post("/api/chat/probe").header("Authorization",
                bearer(principal(TokenAudience.ADMIN, Set.of("chat:use", OBSERVE_PERMISSION)))))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("/api/auth/me 接受已认证的用户端或管理端 token")
    void authMeAcceptsAuthenticatedAudience() throws Exception {
        mockMvc().perform(post("/api/auth/me"))
            .andExpect(status().isUnauthorized());
        mockMvc().perform(post("/api/auth/me").header("Authorization",
                bearer(principal(TokenAudience.CHAT, Set.of("chat:use")))))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("me-ok")));
        mockMvc().perform(post("/api/auth/me").header("Authorization",
                bearer(principal(TokenAudience.ADMIN, Set.of("console:access")))))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("管理接口无 token 时 401，用户端 token 时 403（用途隔离）")
    void manageRequiresAdminAudience() throws Exception {
        mockMvc().perform(post("/manage/probe"))
            .andExpect(status().isUnauthorized());

        mockMvc().perform(post("/manage/probe")
                .header("Authorization", bearer(principal(TokenAudience.CHAT, Set.of("chat:use", OBSERVE_PERMISSION)))))
            .andExpect(status().isForbidden())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("没有该操作的权限")));
    }

    @Test
    @DisplayName("管理端 token 缺接口要求的权限时 403，持有权限时放行")
    void manageRequiresEndpointPermission() throws Exception {
        mockMvc().perform(post("/manage/probe")
                .header("Authorization", bearer(principal(TokenAudience.ADMIN, Set.of("console:access")))))
            .andExpect(status().isForbidden());

        mockMvc().perform(post("/manage/probe")
                .header("Authorization", bearer(principal(TokenAudience.ADMIN, Set.of("console:access", OBSERVE_PERMISSION)))))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("manage-ok")));
    }

    @Test
    @DisplayName("未声明权限要求的管理接口即使持有全部权限也拒绝")
    void undeclaredManageEndpointIsDenied() throws Exception {
        mockMvc().perform(post("/manage/probe/undeclared")
                .header("Authorization", bearer(principal(TokenAudience.ADMIN, Set.of(OBSERVE_PERMISSION, "console:access")))))
            .andExpect(status().isForbidden())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("未声明权限要求")));
    }

    @Test
    @DisplayName("评测快照导出不再是例外：无 token 时 401")
    void evaluationSnapshotIsNoLongerAnonymous() throws Exception {
        mockMvc().perform(post("/manage/evaluation/exchange/snapshot/query"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未列出的路径默认拒绝：匿名 401、已认证 403")
    void unknownPathIsDeniedByDefault() throws Exception {
        mockMvc().perform(post("/internal/anything"))
            .andExpect(status().isUnauthorized());
        mockMvc().perform(post("/internal/anything")
                .header("Authorization", bearer(principal(TokenAudience.CHAT, Set.of("chat:use")))))
            .andExpect(status().isForbidden());
        mockMvc().perform(post("/actuator/env"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SSE 的异步分发与错误分发不再按 URL 规则判定（响应已提交，不能再改为 403）")
    void asyncAndErrorDispatchesAreNotAuthorizedAgain() throws Exception {
        // 真实场景：一次已授权的流式对话在收尾时进入异步分发，此时过滤器链上已没有认证主体。
        mockMvc().perform(withDispatcherType(post("/api/chat/probe"), DispatcherType.ASYNC))
            .andExpect(status().isOk());
        mockMvc().perform(withDispatcherType(post("/manage/probe"), DispatcherType.ASYNC))
            .andExpect(status().isOk());
        mockMvc().perform(withDispatcherType(post("/api/chat/probe"), DispatcherType.ERROR))
            .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder withDispatcherType(MockHttpServletRequestBuilder builder,
                                                            DispatcherType dispatcherType) {
        return builder.with(request -> {
            request.setDispatcherType(dispatcherType);
            return request;
        });
    }

    @Test
    @DisplayName("伪造或过期 token 一律视为未认证")
    void invalidTokenIsRejected() throws Exception {
        mockMvc().perform(post("/api/chat/probe").header("Authorization", "Bearer not-a-jwt"))
            .andExpect(status().isUnauthorized());
        mockMvc().perform(post("/manage/probe").header("Authorization", "Bearer a.b.c"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("没有 Bearer 前缀的 JWT 不建立认证")
    void rawJwtWithoutBearerIsIgnored() throws Exception {
        String raw = jwtTokenService.generateToken(principal(TokenAudience.CHAT, Set.of("chat:use")));
        mockMvc().perform(post("/api/chat/probe").header("Authorization", raw))
            .andExpect(status().isUnauthorized());
    }

    private String bearer(AuthenticatedPrincipal principal) {
        return "Bearer " + jwtTokenService.generateToken(principal);
    }

    private AuthenticatedPrincipal principal(TokenAudience audience, Set<String> permissions) {
        return new AuthenticatedPrincipal(1L, 1L, "tester", audience, Set.of(1L), permissions);
    }

    /** token 服务与签名密钥的测试装配（密钥固定，不读数据库配置）。 */
    @TestConfiguration
    static class TokenSupport {

        @Bean
        @Primary
        AdminAuthProperties adminAuthProperties() {
            AdminAuthProperties properties = new AdminAuthProperties();
            properties.setTokenSecret("filter-chain-test-secret");
            properties.setTokenExpireMinutes(720L);
            return properties;
        }

        @Bean
        @Primary
        JwtTokenService jwtTokenService(AdminAuthProperties adminAuthProperties) {
            return new JwtTokenService(adminAuthProperties);
        }
    }

}
