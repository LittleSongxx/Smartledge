package org.smartledge.ai.chatagent.service;

import org.smartledge.ai.auth.support.AuthFailureException;
import org.smartledge.database.tenant.RequestIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationIdentityServiceTest {

    @Mock
    private ConversationArchiveStore conversationArchiveStore;

    private ConversationIdentityService service() {
        return new ConversationIdentityService(conversationArchiveStore);
    }

    @Test
    @DisplayName("空 id 由服务端签发")
    void issuesWhenBlank() {
        String issued = service().resolveForLaunch("", identity(3L));
        assertThat(issued).isNotBlank();
        assertThat(issued).doesNotContain("-");
    }

    @Test
    @DisplayName("已属于当前用户的 id 续用")
    void reusesOwnedId() {
        when(conversationArchiveStore.findOwnerUserId("mine")).thenReturn(Optional.of(3L));
        assertThat(service().resolveForLaunch("mine", identity(3L))).isEqualTo("mine");
    }

    @Test
    @DisplayName("他租户占用或属他人时拒绝")
    void rejectsForeignOrOtherTenant() {
        when(conversationArchiveStore.findOwnerUserId("theirs")).thenReturn(Optional.of(9L));
        assertThatThrownBy(() -> service().resolveForLaunch("theirs", identity(3L)))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("不属于当前账号");

        when(conversationArchiveStore.findOwnerUserId("shared")).thenReturn(Optional.empty());
        when(conversationArchiveStore.existsInOtherTenant("shared", 1L)).thenReturn(true);
        assertThatThrownBy(() -> service().resolveForLaunch("shared", identity(3L)))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("不属于当前账号");
    }

    @Test
    @DisplayName("未知客户端自选 id 被忽略并重新签发")
    void ignoresUnknownClientChosenId() {
        when(conversationArchiveStore.findOwnerUserId("guessable")).thenReturn(Optional.empty());
        when(conversationArchiveStore.existsInOtherTenant("guessable", 1L)).thenReturn(false);

        String issued = service().resolveForLaunch("guessable", identity(3L));
        assertThat(issued).isNotEqualTo("guessable");
        assertThat(issued).isNotBlank();
    }

    private RequestIdentity identity(Long userId) {
        return new RequestIdentity(1L, userId, "user-" + userId, Set.of(3L), Set.of("chat:use"));
    }
}
