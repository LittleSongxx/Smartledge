package org.smartledge.ai.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baidu.fsg.uid.UidGenerator;
import org.smartledge.ai.manage.data.SuperAgentDocumentAcl;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentAclMapper;
import org.smartledge.ai.manage.support.MybatisLambdaCacheTestSupport;
import org.smartledge.database.tenant.RequestIdentity;
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

import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档 ACL 写入口的语义测试（S23-B3）。
 *
 * <p>锁定的行为是"一份文档 + 一个主体只有一行"：`uk_document_acl(document_id, principal_type, principal_id)`
 * 让再次授予**只能**是改权限（或复活已撤销的行），不能是插入第二行。这条语义是界面上
 * "改权限"和"撤销后重新授予"能工作的前提，也避免了"先删后插"的第二条写路径。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MybatisDocumentAclStoreWritePathTest {

    private static final Long TENANT_ID = 1L;

    private static final Long DOCUMENT_ID = 2521999367372627969L;

    private static final Long ROLE_ID = 2L;

    @Mock
    private SuperAgentDocumentAclMapper documentAclMapper;

    @Mock
    private UidGenerator uidGenerator;

    @InjectMocks
    private MybatisDocumentAclStore store;

    private final RequestIdentity identity = new RequestIdentity(TENANT_ID, 1L, "admin", Set.of(1L), Set.of());

    @BeforeAll
    static void initMybatisMetadata() {
        MybatisLambdaCacheTestSupport.initialize(SuperAgentDocumentAcl.class);
    }

    private SuperAgentDocumentAcl existingRow(int status) {
        SuperAgentDocumentAcl row = new SuperAgentDocumentAcl();
        row.setId(7001L);
        row.setTenantId(TENANT_ID);
        row.setDocumentId(DOCUMENT_ID);
        row.setPrincipalType(SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE);
        row.setPrincipalId(ROLE_ID);
        row.setPermission(SuperAgentDocumentAcl.PERMISSION_READ);
        row.setStatus(status);
        return row;
    }

    @Test
    @DisplayName("同一主体再次授予是改权限：更新既有行，不插入第二行")
    void grantsByUpdatingExistingRow() {
        when(documentAclMapper.selectOne(any())).thenReturn(existingRow(1));

        store.grant(DOCUMENT_ID, identity, SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE, ROLE_ID,
            SuperAgentDocumentAcl.PERMISSION_MANAGE, 1L);

        verify(documentAclMapper, never()).insert(any(SuperAgentDocumentAcl.class));
        verify(documentAclMapper).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("已撤销的行在重新授予时被复活，而不是插入新行")
    void regrantRevivesRevokedRow() {
        when(documentAclMapper.selectOne(any())).thenReturn(existingRow(0));

        store.grant(DOCUMENT_ID, identity, SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE, ROLE_ID,
            SuperAgentDocumentAcl.PERMISSION_WRITE, 1L);

        verify(documentAclMapper, never()).insert(any(SuperAgentDocumentAcl.class));
        verify(documentAclMapper).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("首次授予（没有既有行）才插入，且带上租户与授权人")
    void insertsWhenNoExistingRow() {
        when(documentAclMapper.selectOne(any())).thenReturn(null);
        when(uidGenerator.getUid()).thenReturn(8001L);

        store.grant(DOCUMENT_ID, identity, SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER, 42L,
            SuperAgentDocumentAcl.PERMISSION_READ, 1L);

        ArgumentCaptor<SuperAgentDocumentAcl> captor = ArgumentCaptor.forClass(SuperAgentDocumentAcl.class);
        verify(documentAclMapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(captor.getValue().getPermission()).isEqualTo(SuperAgentDocumentAcl.PERMISSION_READ);
        assertThat(captor.getValue().getGrantedBy()).isEqualTo(1L);
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        verify(documentAclMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("撤销是软删：写 status=0，不物理删除")
    void revokeSoftDeletes() {
        when(documentAclMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        boolean revoked = store.revoke(DOCUMENT_ID, SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE, ROLE_ID, identity);

        assertThat(revoked).isTrue();
        verify(documentAclMapper, never()).delete(any(Wrapper.class));
        verify(documentAclMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("撤销一个本来就没有有效授权的主体：返回 false，不产生写入")
    void revokeReportsNoChange() {
        when(documentAclMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        assertThat(store.revoke(DOCUMENT_ID, SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE, ROLE_ID, identity)).isFalse();
    }

    @Test
    @DisplayName("缺少身份或入参时授予抛错，调用方能知道失败")
    void rejectsIncompleteWrites() {
        assertThatThrownBy(() -> store.grant(DOCUMENT_ID, null, SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER, 1L,
            SuperAgentDocumentAcl.PERMISSION_READ, 1L))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("授权参数不完整");
        assertThatThrownBy(() -> store.grant(DOCUMENT_ID, identity, SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER, null,
            SuperAgentDocumentAcl.PERMISSION_READ, 1L))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("授权参数不完整");

        assertThat(store.revoke(DOCUMENT_ID, null, 1L, identity)).isFalse();
        assertThat(store.listByDocument(DOCUMENT_ID, null)).isEmpty();
        verify(documentAclMapper, never()).insert(any(SuperAgentDocumentAcl.class));
        verify(documentAclMapper, never()).update(any(), any(Wrapper.class));
        verify(documentAclMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("唯一键冲突时升级为更新，而不是静默失败")
    void uniqueKeyConflictRetriesAsUpdate() {
        when(documentAclMapper.selectOne(any())).thenReturn(null, existingRow(0));
        when(documentAclMapper.insert(any(SuperAgentDocumentAcl.class)))
            .thenThrow(new DuplicateKeyException("uk_document_acl"));
        when(uidGenerator.getUid()).thenReturn(8002L);

        store.grant(DOCUMENT_ID, identity, SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE, ROLE_ID,
            SuperAgentDocumentAcl.PERMISSION_WRITE, 1L);

        verify(documentAclMapper).insert(any(SuperAgentDocumentAcl.class));
        verify(documentAclMapper).update(any(), any(Wrapper.class));
        verify(documentAclMapper, times(2)).selectOne(any());
    }
}
