package org.smartledge.ai.auth.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.smartledge.ai.auth.service.AuthAccountStore;
import org.smartledge.exception.SuperAgentFrameException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthSessionValidatorTest {

    @Mock
    private AuthAccountStore authAccountStore;

    private AuthenticatedPrincipal principal(long version) {
        return new AuthenticatedPrincipal(1L, 9L, "alice", TokenAudience.ADMIN,
            Set.of(1L), Set.of("console:access"), version, "jti-1");
    }

    @Test
    @DisplayName("用户启用、租户启用且版本一致时放行")
    void acceptsActiveMatchingVersion() {
        when(authAccountStore.findSessionAccount(1L, 9L))
            .thenReturn(Optional.of(new AuthAccountStore.SessionAccount(1L, 9L, true, true, 3L)));

        assertThatCode(() -> new AuthSessionValidator(authAccountStore).validate(principal(3L)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("停用用户后已签发 token 失效")
    void rejectsDisabledUser() {
        when(authAccountStore.findSessionAccount(1L, 9L))
            .thenReturn(Optional.of(new AuthAccountStore.SessionAccount(1L, 9L, false, true, 1L)));

        assertThatThrownBy(() -> new AuthSessionValidator(authAccountStore).validate(principal(1L)))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("登录凭证已失效");
    }

    @Test
    @DisplayName("停用租户后已签发 token 失效")
    void rejectsDisabledTenant() {
        when(authAccountStore.findSessionAccount(1L, 9L))
            .thenReturn(Optional.of(new AuthAccountStore.SessionAccount(1L, 9L, true, false, 1L)));

        assertThatThrownBy(() -> new AuthSessionValidator(authAccountStore).validate(principal(1L)))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("登录凭证已失效");
    }

    @Test
    @DisplayName("改角色/登出升版本后旧 token 失效")
    void rejectsStaleTokenVersion() {
        when(authAccountStore.findSessionAccount(1L, 9L))
            .thenReturn(Optional.of(new AuthAccountStore.SessionAccount(1L, 9L, true, true, 4L)));

        assertThatThrownBy(() -> new AuthSessionValidator(authAccountStore).validate(principal(3L)))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("登录凭证已失效");
        verify(authAccountStore).findSessionAccount(1L, 9L);
    }
}
