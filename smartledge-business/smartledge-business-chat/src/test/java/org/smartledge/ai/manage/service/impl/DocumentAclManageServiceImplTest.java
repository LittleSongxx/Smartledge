package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.auth.data.AuthRole;
import org.smartledge.ai.auth.data.AuthUserAccount;
import org.smartledge.ai.auth.mapper.AuthRoleMapper;
import org.smartledge.ai.auth.mapper.AuthUserAccountMapper;
import org.smartledge.ai.auth.support.AuthFailureException;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentAcl;
import org.smartledge.ai.manage.dto.DocumentAclGrantDto;
import org.smartledge.ai.manage.dto.DocumentAclQueryDto;
import org.smartledge.ai.manage.dto.DocumentAclRevokeDto;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.service.DocumentAclStore;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.exception.SuperAgentFrameException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档授权接口的两层资格测试（S23-B3）。
 *
 * <p>要锁定的核心不变量：权限编码 {@code document:acl:manage}（能不能做授权管理）与文档级 ACL 的
 * MANAGE（能不能对**这份**文档授权）是两道独立门槛。只有前者就能授权任意文档，等于把租户级能力
 * 放大成"看得见的每份文档都能改"；只有后者则连入口都进不来。缺任何一条都必须在触碰数据前拒绝。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentAclManageServiceImplTest {

    private static final Long TENANT_ID = 1L;

    private static final Long DOCUMENT_ID = 2521999367372627969L;

    private static final Long CURATOR_USER_ID = 2L;

    private static final Long CURATOR_ROLE_ID = 2L;

    @Mock
    private DocumentAclStore documentAclStore;

    @Mock
    private SuperAgentDocumentMapper documentMapper;

    @Mock
    private AuthUserAccountMapper userAccountMapper;

    @Mock
    private AuthRoleMapper roleMapper;

    @InjectMocks
    private DocumentAclManageServiceImpl service;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 以 curator（持有 document:acl:manage 编码）身份登录。 */
    private void signInAsCurator() {
        TenantContext.setIdentity(new RequestIdentity(TENANT_ID, CURATOR_USER_ID, "curator",
            Set.of(CURATOR_ROLE_ID), Set.of("console:access", "document:acl:manage", "document:upload")));
    }

    private SuperAgentDocument document() {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(DOCUMENT_ID);
        document.setDocumentName("123");
        document.setStatus(1);
        return document;
    }

    private void documentExists() {
        when(documentMapper.selectOne(any())).thenReturn(document());
    }

    private DocumentAclStore.DocumentAclRecord roleRow() {
        return new DocumentAclStore.DocumentAclRecord(
            9001L, SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE, CURATOR_ROLE_ID,
            SuperAgentDocumentAcl.PERMISSION_MANAGE, 1L, true);
    }

    @Test
    @DisplayName("有编码权限但没有文档级 MANAGE 时拒绝（不越权）")
    void refusesWhenCallerLacksDocumentManage() {
        signInAsCurator();
        documentExists();
        when(documentAclStore.manageableDocumentIds(anyList(), any())).thenReturn(Set.of());
        when(documentAclStore.writableDocumentIds(anyList(), any())).thenReturn(Set.of(DOCUMENT_ID));
        when(documentAclStore.visibleDocumentIds(anyList(), any())).thenReturn(Set.of(DOCUMENT_ID));

        DocumentAclQueryDto dto = new DocumentAclQueryDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));

        assertThatThrownBy(() -> service.query(dto))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("没有该文档的授权管理权限");
        // 只在文档级判定，"看起来有写权限"不构成授权资格。
        verify(documentAclStore, never()).listByDocument(anyLong(), any());
        verify(documentAclStore, never()).grant(anyLong(), any(), anyString(), anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("跨租户 / 不存在的文档同义：都是\"文档不存在\"，不泄漏存在性")
    void treatsForeignDocumentAsAbsent() {
        signInAsCurator();
        when(documentMapper.selectOne(any())).thenReturn(null);

        DocumentAclQueryDto dto = new DocumentAclQueryDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));

        assertThatThrownBy(() -> service.query(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("文档不存在。");
    }

    @Test
    @DisplayName("查询返回授权行与主体名字，并回显调用者自己的有效权限")
    void queryReturnsEntriesWithPrincipalLabels() {
        signInAsCurator();
        documentExists();
        when(documentAclStore.manageableDocumentIds(anyList(), any())).thenReturn(Set.of(DOCUMENT_ID));
        when(documentAclStore.listByDocument(anyLong(), any())).thenReturn(List.of(roleRow()));

        AuthRole curatorRole = new AuthRole();
        curatorRole.setId(CURATOR_ROLE_ID);
        curatorRole.setTenantId(TENANT_ID);
        curatorRole.setRoleCode("CURATOR");
        curatorRole.setRoleName("知识库管理员");
        when(roleMapper.selectList(any())).thenReturn(List.of(curatorRole));

        DocumentAclQueryDto dto = new DocumentAclQueryDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));

        var view = service.query(dto);

        assertThat(view.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(view.getCallerPermission()).isEqualTo(SuperAgentDocumentAcl.PERMISSION_MANAGE);
        assertThat(view.getEntries()).hasSize(1);
        assertThat(view.getEntries().get(0).getPrincipalType()).isEqualTo(SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE);
        assertThat(view.getEntries().get(0).getPrincipalName()).isEqualTo("知识库管理员（角色）");
        assertThat(view.getEntries().get(0).getPrincipalRef()).isEqualTo("CURATOR");
        assertThat(view.getEntries().get(0).getEnabled()).isTrue();
    }

    @Test
    @DisplayName("授予时校验权限取值、主体类型与主体存在性")
    void validatesGrantInput() {
        signInAsCurator();
        documentExists();
        when(documentAclStore.manageableDocumentIds(anyList(), any())).thenReturn(Set.of(DOCUMENT_ID));

        DocumentAclGrantDto badPermission = grantDto("USER", String.valueOf(CURATOR_USER_ID), "OWNER");
        assertThatThrownBy(() -> service.grant(badPermission))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("权限只能是 READ / WRITE / MANAGE。");

        DocumentAclGrantDto badType = grantDto("GROUP", String.valueOf(CURATOR_USER_ID), "READ");
        assertThatThrownBy(() -> service.grant(badType))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("主体类型只能是 USER 或 ROLE。");

        DocumentAclGrantDto foreignPrincipal = grantDto("USER", "8900000000000000001", "READ");
        when(userAccountMapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> service.grant(foreignPrincipal))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("被授权的主体不在当前租户内。");

        // 三条非法输入都不允许落到写路径。
        verify(documentAclStore, never()).grant(anyLong(), any(), anyString(), anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("授予成功：写路径带上调用者身份，并回显最新列表")
    void grantsWithCallerIdentity() {
        signInAsCurator();
        documentExists();
        when(documentAclStore.manageableDocumentIds(anyList(), any())).thenReturn(Set.of(DOCUMENT_ID));
        when(userAccountMapper.selectCount(any())).thenReturn(1L);
        when(documentAclStore.listByDocument(anyLong(), any())).thenReturn(List.of());
        when(userAccountMapper.selectList(any())).thenReturn(List.of());

        DocumentAclGrantDto dto = grantDto("USER", String.valueOf(CURATOR_USER_ID), "write");
        service.grant(dto);

        // 权限编码大小写归一化后写入，授权人是当前主体（不由请求体决定）。
        verify(documentAclStore).grant(DOCUMENT_ID, TenantContext.getIdentity(),
            SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER, CURATOR_USER_ID,
            SuperAgentDocumentAcl.PERMISSION_WRITE, CURATOR_USER_ID);
    }

    @Test
    @DisplayName("撤销请求必须指向一份有效授权")
    void revokeRequiresExistingGrant() {
        signInAsCurator();
        documentExists();
        when(documentAclStore.manageableDocumentIds(anyList(), any())).thenReturn(Set.of(DOCUMENT_ID));
        when(documentAclStore.revoke(anyLong(), anyString(), anyLong(), any())).thenReturn(false);

        DocumentAclRevokeDto dto = new DocumentAclRevokeDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));
        dto.setPrincipalType("ROLE");
        dto.setPrincipalId(String.valueOf(CURATOR_ROLE_ID));

        assertThatThrownBy(() -> service.revoke(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("没有有效授权");
    }

    @Test
    @DisplayName("撤销会把自己锁在门外时整体回滚")
    void refusesRevokeThatStrandsTheCaller() {
        signInAsCurator();
        documentExists();
        // 第一次判定：调用者可管理；撤销后重新判定：不再可管理 → 必须回滚。
        when(documentAclStore.manageableDocumentIds(anyList(), any()))
            .thenReturn(Set.of(DOCUMENT_ID))
            .thenReturn(Set.of());
        when(documentAclStore.revoke(anyLong(), anyString(), anyLong(), any())).thenReturn(true);

        DocumentAclRevokeDto dto = new DocumentAclRevokeDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));
        dto.setPrincipalType("USER");
        dto.setPrincipalId(String.valueOf(CURATOR_USER_ID));

        assertThatThrownBy(() -> service.revoke(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("会让你失去这份文档的管理权限");
    }

    @Test
    @DisplayName("没有认证主体时拒绝（fail closed）")
    void refusesWithoutIdentity() {
        TenantContext.clear();

        DocumentAclQueryDto dto = new DocumentAclQueryDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));

        assertThatThrownBy(() -> service.query(dto))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("请先登录");
    }

    private DocumentAclGrantDto grantDto(String principalType, String principalId, String permission) {
        DocumentAclGrantDto dto = new DocumentAclGrantDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));
        dto.setPrincipalType(principalType);
        dto.setPrincipalId(principalId);
        dto.setPermission(permission);
        return dto;
    }
}
