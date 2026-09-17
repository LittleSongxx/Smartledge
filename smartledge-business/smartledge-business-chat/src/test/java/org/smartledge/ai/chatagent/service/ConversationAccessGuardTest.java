package org.smartledge.ai.chatagent.service;

import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.exception.SuperAgentFrameException;
import org.junit.jupiter.api.AfterEach;
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

/**
 * 会话归属守卫的不变量测试。
 *
 * <p>对应 B3 要求「对话记忆摘要等派生内容同受约束」。租户边界挡不住同租户内的越权：
 * 任何用户拿到 conversationId 就能读别人的会话、记忆摘要与检索观测。归属判定必须 fail closed，
 * 且不能通过错误文案区分"会话不存在"与"会话属于他人"（否则等于提供了存在性探测接口）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ConversationAccessGuardTest {

    @Mock
    private ConversationArchiveStore conversationArchiveStore;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ConversationAccessGuard guard() {
        return new ConversationAccessGuard(conversationArchiveStore);
    }

    @Test
    @DisplayName("会话属于当前身份时放行")
    void ownedConversationPasses() {
        authenticate(3L);
        when(conversationArchiveStore.findOwnerUserId("c-1")).thenReturn(Optional.of(3L));

        guard().requireOwned("c-1");
    }

    @Test
    @DisplayName("会话属于他人时拒绝，且不泄漏会话是否存在")
    void otherUsersConversationIsRejected() {
        authenticate(3L);
        when(conversationArchiveStore.findOwnerUserId("c-1")).thenReturn(Optional.of(4L));
        when(conversationArchiveStore.findOwnerUserId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard().requireOwned("c-1"))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不属于当前账号");
        assertThatThrownBy(() -> guard().requireOwned("missing"))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不属于当前账号");
    }

    @Test
    @DisplayName("无归属的历史会话任何人都不可读，且不会被后来的请求认领")
    void unownedConversationIsNeverClaimable() {
        authenticate(3L);
        when(conversationArchiveStore.findOwnerUserId("legacy")).thenReturn(Optional.of(0L));

        assertThatThrownBy(() -> guard().requireOwned("legacy"))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不属于当前账号");
        assertThatThrownBy(() -> guard().requireOwnedOrNew("legacy"))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不属于当前账号");
    }

    @Test
    @DisplayName("新会话允许创建，已存在的会话必须属于当前身份")
    void newConversationAllowedButExistingMustBeOwned() {
        authenticate(3L);
        when(conversationArchiveStore.findOwnerUserId("new")).thenReturn(Optional.empty());
        when(conversationArchiveStore.existsInOtherTenant("new", 1L)).thenReturn(false);
        when(conversationArchiveStore.findOwnerUserId("mine")).thenReturn(Optional.of(3L));

        guard().requireOwnedOrNew("new");
        guard().requireOwnedOrNew("mine");
    }

    @Test
    @DisplayName("他租户已占用同一 dialogue_code 时拒绝，且不泄漏占用方")
    void otherTenantOccupationIsRejected() {
        authenticate(3L);
        when(conversationArchiveStore.findOwnerUserId("shared")).thenReturn(Optional.empty());
        when(conversationArchiveStore.existsInOtherTenant("shared", 1L)).thenReturn(true);

        assertThatThrownBy(() -> guard().requireOwnedOrNew("shared"))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不属于当前账号");
    }

    @Test
    @DisplayName("未认证时拒绝，且不读取归属（避免无身份查询会话表）")
    void unauthenticatedIsRejected() {
        TenantContext.set(1L);

        assertThatThrownBy(() -> guard().requireOwned("c-1"))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("请先登录");
        assertThatThrownBy(() -> guard().requireCurrentUserId())
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("请先登录");
    }

    @Test
    @DisplayName("当前用户 id 来自认证主体，不来自请求参数")
    void currentUserIdComesFromIdentity() {
        authenticate(3L);
        assertThat(guard().requireCurrentUserId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("管理观测允许查看本租户内他人会话")
    void observeAllowsOtherUsersSessionInTenant() {
        authenticate(3L);
        when(conversationArchiveStore.findOwnerUserId("theirs")).thenReturn(Optional.of(9L));
        guard().requireVisibleInTenant("theirs");
    }

    @Test
    @DisplayName("试用账号的管理观测不能查看他人会话")
    void demoObserveCannotSeeOtherUsersSession() {
        authenticate(201L, Set.of("chat:use", "observe:read", "portfolio:demo"));
        when(conversationArchiveStore.findOwnerUserId("theirs")).thenReturn(Optional.of(9L));
        when(conversationArchiveStore.findOwnerUserId("mine")).thenReturn(Optional.of(201L));

        assertThatThrownBy(() -> guard().requireVisibleInTenant("theirs"))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不属于当前账号");
        guard().requireVisibleInTenant("mine");
        assertThat(guard().observeOwnerFilter()).isEqualTo(201L);
    }

    @Test
    @DisplayName("非试用管理观测不过滤主人")
    void nonDemoObserveOwnerFilterIsOpen() {
        authenticate(3L);
        assertThat(guard().observeOwnerFilter()).isNull();
    }

    @Test
    @DisplayName("管理观测对不存在的会话 fail closed")
    void observeRejectsMissingSession() {
        authenticate(3L);
        when(conversationArchiveStore.findOwnerUserId("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard().requireVisibleInTenant("missing"))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不属于当前账号");
    }

    private void authenticate(Long userId) {
        authenticate(userId, Set.of("chat:use"));
    }

    private void authenticate(Long userId, Set<String> permissions) {
        TenantContext.setIdentity(new RequestIdentity(1L, userId, "user-" + userId, Set.of(3L), permissions));
    }
}
