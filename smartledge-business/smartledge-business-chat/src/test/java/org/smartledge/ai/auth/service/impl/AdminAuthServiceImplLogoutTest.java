package org.smartledge.ai.auth.service.impl;

import org.smartledge.ai.auth.service.AuthAccountStore;
import org.smartledge.ai.auth.service.UserAuthService;
import org.smartledge.ai.auth.support.AuthFailureException;
import org.smartledge.ai.auth.support.AuthenticatedPrincipal;
import org.smartledge.ai.auth.support.TokenAudience;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceImplLogoutTest {

    @Mock
    private UserAuthService userAuthService;

    @Mock
    private AuthAccountStore authAccountStore;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("登出升高 token 版本，使当前用户全部未过期 JWT 失效")
    void logoutIncrementsTokenVersion() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            1L, 9L, "admin", TokenAudience.ADMIN, Set.of(1L), Set.of("console:access"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));

        new AdminAuthServiceImpl(userAuthService, authAccountStore).logout();

        verify(authAccountStore).incrementTokenVersion(1L, 9L);
    }

    @Test
    @DisplayName("未登录登出拒绝")
    void logoutRequiresPrincipal() {
        assertThatThrownBy(() -> new AdminAuthServiceImpl(userAuthService, authAccountStore).logout())
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("请先登录");
    }
}
