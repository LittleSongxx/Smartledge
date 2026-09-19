package org.smartledge.ai.auth.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.smartledge.ai.auth.data.AuthRole;
import org.smartledge.ai.auth.data.AuthUserAccount;
import org.smartledge.ai.auth.data.AuthUserRole;
import org.smartledge.ai.auth.dto.TenantMemberSaveDto;
import org.smartledge.ai.auth.dto.TenantMemberStatusUpdateDto;
import org.smartledge.ai.auth.mapper.AuthPermissionMapper;
import org.smartledge.ai.auth.mapper.AuthRoleMapper;
import org.smartledge.ai.auth.mapper.AuthRolePermissionMapper;
import org.smartledge.ai.auth.mapper.AuthUserAccountMapper;
import org.smartledge.ai.auth.mapper.AuthUserRoleMapper;
import org.smartledge.ai.auth.support.AuthFailureException;
import org.smartledge.ai.auth.service.AuthAccountStore;
import org.smartledge.ai.auth.support.PasswordVerifier;
import org.smartledge.ai.manage.support.MybatisLambdaCacheTestSupport;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.exception.SuperAgentFrameException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户内成员管理的边界测试（S23-B2）。
 *
 * <p>锁定的行为：作用域只来自认证主体（越界的成员/角色一律拒绝，不静默忽略）、
 * 口令只以哈希入库、以及两条自锁防护（不能停用自己、不能改自己的角色）。
 * 这些是"管理界面能不能安全交给租户管理员"的前提。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantMemberManageServiceImplTest {

    private static final Long TENANT_ID = 1L;

    private static final Long ADMIN_USER_ID = 1L;

    private static final Long MEMBER_USER_ID = 42L;

    private static final Long ADMIN_ROLE_ID = 1L;

    private static final Long CURATOR_ROLE_ID = 2L;

    private static final Long FOREIGN_ROLE_ID = 900L;

    @Mock
    private AuthUserAccountMapper userAccountMapper;

    @Mock
    private AuthUserRoleMapper userRoleMapper;

    @Mock
    private AuthRoleMapper roleMapper;

    @Mock
    private AuthRolePermissionMapper rolePermissionMapper;

    @Mock
    private AuthPermissionMapper permissionMapper;

    @Mock
    private PasswordVerifier passwordVerifier;

    @Mock
    private UidGenerator uidGenerator;

    @Mock
    private AuthAccountStore authAccountStore;

    @InjectMocks
    private TenantMemberManageServiceImpl service;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @BeforeAll
    static void initMybatisMetadata() {
        // 纯单元测试没有 Spring 上下文，lambda 包装器需要的实体元数据要显式建立。
        MybatisLambdaCacheTestSupport.initialize(AuthUserAccount.class, AuthUserRole.class, AuthRole.class);
    }

    private void signInAs(Long userId, String username) {
        TenantContext.setIdentity(new RequestIdentity(
            TENANT_ID, userId, username, Set.of(1L), Set.of("user:manage", "console:access")));
    }

    private AuthUserAccount member(Long id, String username, int status) {
        AuthUserAccount account = new AuthUserAccount();
        account.setId(id);
        account.setTenantId(TENANT_ID);
        account.setUsername(username);
        account.setDisplayName(username);
        account.setPasswordHash("$2a$10$existing");
        account.setFailedAttempts(0);
        account.setStatus(status);
        return account;
    }

    private AuthRole curatorRole() {
        AuthRole role = new AuthRole();
        role.setId(CURATOR_ROLE_ID);
        role.setTenantId(TENANT_ID);
        role.setRoleCode("CURATOR");
        role.setRoleName("知识库管理员");
        role.setStatus(1);
        return role;
    }

    private AuthRole adminRole() {
        AuthRole role = new AuthRole();
        role.setId(ADMIN_ROLE_ID);
        role.setTenantId(TENANT_ID);
        role.setRoleCode("ADMIN");
        role.setRoleName("租户管理员");
        role.setStatus(1);
        return role;
    }

    @Test
    @DisplayName("没有认证主体时拒绝（fail closed）")
    void rejectsWithoutIdentity() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.queryPage(null))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("请先登录");
    }

    @Test
    @DisplayName("新建成员：口令只以哈希入库，角色绑定按给定集合写入")
    void createsMemberWithHashedPasswordOnly() {
        signInAs(ADMIN_USER_ID, "admin");
        when(uidGenerator.getUid()).thenReturn(1001L, 2001L);
        when(passwordVerifier.encode("user123456")).thenReturn("$2a$10$encoded");
        when(userAccountMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectList(any())).thenReturn(List.of(adminRole(), curatorRole()));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        TenantMemberSaveDto dto = new TenantMemberSaveDto();
        dto.setUsername("curator-b");
        dto.setDisplayName("租户B 知识库管理员");
        dto.setPassword("user123456");
        dto.setRoleIds(List.of(String.valueOf(CURATOR_ROLE_ID)));

        service.save(dto);

        ArgumentCaptor<AuthUserAccount> accountCaptor = ArgumentCaptor.forClass(AuthUserAccount.class);
        verify(userAccountMapper).insert(accountCaptor.capture());
        AuthUserAccount inserted = accountCaptor.getValue();
        assertThat(inserted.getPasswordHash()).isEqualTo("$2a$10$encoded");
        // 明文口令不出现在实体的任何字段里。
        assertThat(inserted.getPasswordHash()).doesNotContain("user123456");
        assertThat(inserted.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(inserted.getUsername()).isEqualTo("curator-b");
        assertThat(inserted.getStatus()).isEqualTo(1);

        ArgumentCaptor<AuthUserRole> relationCaptor = ArgumentCaptor.forClass(AuthUserRole.class);
        verify(userRoleMapper).insert(relationCaptor.capture());
        assertThat(relationCaptor.getValue().getRoleId()).isEqualTo(CURATOR_ROLE_ID);
        assertThat(relationCaptor.getValue().getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("越界的角色 id 整体拒绝，不创建成员")
    void rejectsRoleFromAnotherTenant() {
        signInAs(ADMIN_USER_ID, "admin");
        when(roleMapper.selectList(any())).thenReturn(List.of(curatorRole()));

        TenantMemberSaveDto dto = new TenantMemberSaveDto();
        dto.setUsername("curator-b");
        dto.setDisplayName("越权尝试");
        dto.setPassword("user123456");
        dto.setRoleIds(List.of(String.valueOf(CURATOR_ROLE_ID), String.valueOf(FOREIGN_ROLE_ID)));

        assertThatThrownBy(() -> service.save(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不属于当前租户的角色");
        verify(userAccountMapper, never()).insert(any(AuthUserAccount.class));
    }

    @Test
    @DisplayName("口令过短或登录名非法时拒绝，不触碰数据")
    void rejectsWeakCredentialAndIllegalUsername() {
        signInAs(ADMIN_USER_ID, "admin");

        TenantMemberSaveDto weakPassword = new TenantMemberSaveDto();
        weakPassword.setUsername("curator-b");
        weakPassword.setDisplayName("短口令");
        weakPassword.setPassword("short");

        assertThatThrownBy(() -> service.save(weakPassword))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("至少 8 位");

        TenantMemberSaveDto illegalName = new TenantMemberSaveDto();
        illegalName.setUsername("租户B管理员");
        illegalName.setDisplayName("中文登录名");
        illegalName.setPassword("user123456");

        assertThatThrownBy(() -> service.save(illegalName))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("登录名只能包含");

        verify(userAccountMapper, never()).insert(any(AuthUserAccount.class));
    }

    @Test
    @DisplayName("不能停用自己，也不能改自己的角色（自锁防护）")
    void refusesToLockTheCallerOut() {
        signInAs(ADMIN_USER_ID, "admin");
        when(userAccountMapper.selectOne(any())).thenReturn(member(ADMIN_USER_ID, "admin", 1));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        TenantMemberStatusUpdateDto statusDto = new TenantMemberStatusUpdateDto();
        statusDto.setId(String.valueOf(ADMIN_USER_ID));
        statusDto.setStatus("0");

        assertThatThrownBy(() -> service.updateStatus(statusDto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不能修改自己的启用状态");

        AuthUserRole relation = new AuthUserRole();
        relation.setId(7L);
        relation.setTenantId(TENANT_ID);
        relation.setUserId(ADMIN_USER_ID);
        relation.setRoleId(1L);
        relation.setStatus(1);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(relation));
        when(roleMapper.selectList(any())).thenReturn(List.of(curatorRole()));

        TenantMemberSaveDto saveDto = new TenantMemberSaveDto();
        saveDto.setId(String.valueOf(ADMIN_USER_ID));
        saveDto.setDisplayName("租户管理员");
        saveDto.setRoleIds(List.of(String.valueOf(CURATOR_ROLE_ID)));

        assertThatThrownBy(() -> service.save(saveDto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("不能修改自己的角色");
        verify(userAccountMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("停用他人：写 status=0 并清除锁定状态")
    void disablesAnotherMember() {
        signInAs(ADMIN_USER_ID, "admin");
        when(userAccountMapper.selectOne(any())).thenReturn(member(MEMBER_USER_ID, "alice", 1));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        TenantMemberStatusUpdateDto dto = new TenantMemberStatusUpdateDto();
        dto.setId(String.valueOf(MEMBER_USER_ID));
        dto.setStatus("0");

        service.updateStatus(dto);

        verify(userAccountMapper).update(any(), any(Wrapper.class));
        verify(authAccountStore).incrementTokenVersion(TENANT_ID, MEMBER_USER_ID);
    }

    @Test
    @DisplayName("不能停用租户内最后一名启用 ADMIN")
    void refusesToDisableLastEnabledAdmin() {
        signInAs(ADMIN_USER_ID, "admin");
        when(userAccountMapper.selectOne(any())).thenReturn(member(MEMBER_USER_ID, "other-admin", 1));
        AuthUserRole relation = new AuthUserRole();
        relation.setUserId(MEMBER_USER_ID);
        relation.setRoleId(ADMIN_ROLE_ID);
        relation.setStatus(1);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(relation));
        when(roleMapper.selectList(any())).thenReturn(List.of(adminRole()));
        when(userAccountMapper.selectCount(any())).thenReturn(1L);

        TenantMemberStatusUpdateDto dto = new TenantMemberStatusUpdateDto();
        dto.setId(String.valueOf(MEMBER_USER_ID));
        dto.setStatus("0");

        assertThatThrownBy(() -> service.updateStatus(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("最后一名启用管理员");
        verify(userAccountMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("CURATOR 不能把别人提成 ADMIN")
    void curatorCannotGrantAdmin() {
        TenantContext.setIdentity(new RequestIdentity(
            TENANT_ID, MEMBER_USER_ID, "curator", Set.of(CURATOR_ROLE_ID), Set.of("user:manage")));
        when(roleMapper.selectList(any())).thenReturn(List.of(adminRole(), curatorRole()));

        TenantMemberSaveDto dto = new TenantMemberSaveDto();
        dto.setUsername("new-admin");
        dto.setDisplayName("越权提升");
        dto.setPassword("user123456");
        dto.setRoleIds(List.of(String.valueOf(ADMIN_ROLE_ID)));

        assertThatThrownBy(() -> service.save(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("高于自己的角色");
        verify(userAccountMapper, never()).insert(any(AuthUserAccount.class));
    }

    @Test
    @DisplayName("跨租户的成员 id 等同于不存在（拒绝且不写）")
    void treatsForeignMemberAsAbsent() {
        signInAs(ADMIN_USER_ID, "admin");
        when(userAccountMapper.selectOne(any())).thenReturn(null);

        TenantMemberStatusUpdateDto dto = new TenantMemberStatusUpdateDto();
        dto.setId("8900000000000000001");
        dto.setStatus("0");

        assertThatThrownBy(() -> service.updateStatus(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("成员不存在");
        verify(userAccountMapper, never()).update(any(), any(Wrapper.class));
    }
}
