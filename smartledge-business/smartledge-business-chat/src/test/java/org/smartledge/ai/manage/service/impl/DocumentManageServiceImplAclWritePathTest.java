package org.smartledge.ai.manage.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentAcl;
import org.smartledge.ai.manage.dto.DocumentUploadDto;
import org.smartledge.ai.manage.support.StoredObjectInfo;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeBase;
import org.smartledge.ai.manage.dto.DocumentDeleteDto;
import org.smartledge.ai.manage.dto.DocumentDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentIndexBuildDto;
import org.smartledge.ai.manage.dto.DocumentPageQueryDto;
import org.smartledge.ai.manage.dto.DocumentParseArtifactQueryDto;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.smartledge.ai.manage.mq.DocumentMessagePublisher;
import org.smartledge.ai.manage.service.DocumentParseRouteProgressCacheService;
import org.smartledge.ai.manage.service.DocumentTaskLogService;
import org.smartledge.ai.manage.service.DocumentAclStore;
import org.smartledge.ai.manage.service.DocumentStorageService;
import org.smartledge.ai.manage.service.KnowledgeBaseManageService;
import org.smartledge.ai.manage.support.DocumentTenantLookup;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.exception.SuperAgentFrameException;
import org.smartledge.ai.auth.support.AuthFailureException;
import org.smartledge.ai.manage.support.MybatisLambdaCacheTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档写路径的 ACL 判定测试。
 *
 * <p>对应 B3 要求「越权用例覆盖写路径」：权限编码（{@code document:delete} 之类）只回答
 * "能不能做这类操作"，文档 ACL 回答"能不能对**这份**文档做"。缺任何一条都必须在触碰数据之前拒绝。
 * 这里同时锁定"有授权时必须放行到业务校验"，避免把门槛写成"永远拒绝"。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentManageServiceImplAclWritePathTest {

    private static final Long DOCUMENT_ID = 2521999367372627969L;

    private static final Long TENANT_ID = 1L;

    private static final Long ADMIN_USER_ID = 1L;

    @Mock
    private SuperAgentDocumentMapper documentMapper;

    @Mock
    private KnowledgeBaseManageService knowledgeBaseManageService;

    @Mock
    private DocumentAclStore documentAclStore;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private UidGenerator uidGenerator;

    @Mock
    private DocumentStorageService storageService;

    @Mock
    private SuperAgentDocumentTaskMapper taskMapper;

    @Mock
    private DocumentTaskLogService taskLogService;

    @Mock
    private DocumentParseRouteProgressCacheService parseRouteProgressCacheService;

    @Mock
    private DocumentMessagePublisher messagePublisher;

    @InjectMocks
    private DocumentManageServiceImpl service;

    @BeforeAll
    static void initMybatisMetadata() {
        MybatisLambdaCacheTestSupport.initialize(SuperAgentDocument.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("没有 MANAGE 授权时删除被拒绝，且不触碰文档数据")
    void deleteRequiresManageGrant() {
        authenticate(ADMIN_USER_ID);
        when(documentAclStore.manageableDocumentIds(any(), any())).thenReturn(Set.of());

        DocumentDeleteDto dto = new DocumentDeleteDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));

        assertThatThrownBy(() -> service.deleteDocument(dto))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("没有删除该文档的权限");

        verify(documentMapper, never()).selectById(anyLong());
        verify(documentMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("有 MANAGE 授权时删除进入业务校验（而不是被权限层拦死）")
    void deletePassesWithManageGrant() {
        authenticate(ADMIN_USER_ID);
        when(documentAclStore.manageableDocumentIds(any(), any())).thenReturn(Set.of(DOCUMENT_ID));
        // 文档不存在：这一步的失败必须是"文档状态/存在性"，说明已经越过权限判定。
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(null);

        DocumentDeleteDto dto = new DocumentDeleteDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));

        assertThatThrownBy(() -> service.deleteDocument(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .isNotInstanceOf(AuthFailureException.class);

        verify(documentAclStore).manageableDocumentIds(eq(List.of(DOCUMENT_ID)), any(RequestIdentity.class));
    }

    @Test
    @DisplayName("没有 WRITE 授权时索引构建被拒绝，且不读文档状态")
    void buildIndexRequiresWriteGrant() {
        authenticate(ADMIN_USER_ID);
        when(documentAclStore.writableDocumentIds(any(), any())).thenReturn(Set.of());

        DocumentIndexBuildDto dto = new DocumentIndexBuildDto();
        dto.setDocumentId(DOCUMENT_ID);
        dto.setPlanId(1000L);

        assertThatThrownBy(() -> service.buildIndex(dto))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("没有修改该文档的权限");

        verify(documentMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("未认证时写路径直接拒绝，不读 ACL（也就无法被空身份绕过）")
    void writePathRequiresIdentity() {
        TenantContext.set(TENANT_ID);

        DocumentDeleteDto dto = new DocumentDeleteDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));

        assertThatThrownBy(() -> service.deleteDocument(dto))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("请先登录");

        verify(documentAclStore, never()).manageableDocumentIds(any(), any());
        verify(documentAclStore, never()).writableDocumentIds(any(), any());
    }

    @Test
    @DisplayName("ACL 判定用的是当前身份（租户 + 用户 + 角色），不是请求参数")
    void aclCheckUsesCurrentIdentity() {
        RequestIdentity identity = authenticate(ADMIN_USER_ID);
        when(documentAclStore.writableDocumentIds(any(), any())).thenReturn(Set.of());
        when(documentMapper.selectById(anyLong())).thenReturn(null);

        DocumentIndexBuildDto dto = new DocumentIndexBuildDto();
        dto.setDocumentId(DOCUMENT_ID);
        dto.setPlanId(1000L);
        assertThatThrownBy(() -> service.buildIndex(dto)).isInstanceOf(AuthFailureException.class);

        verify(documentAclStore).writableDocumentIds(eq(List.of(DOCUMENT_ID)), eq(identity));
    }

    @Test
    @DisplayName("无 ACL 且无 document:read-all 时不能读文档详情")
    void detailRequiresAclWhenNoReadAll() {
        authenticate(ADMIN_USER_ID);
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(DOCUMENT_ID);
        document.setStatus(1);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
        when(documentAclStore.visibleDocumentIds(any(), any())).thenReturn(Set.of());

        DocumentDetailQueryDto dto = new DocumentDetailQueryDto();
        dto.setDocumentId(DOCUMENT_ID);

        assertThatThrownBy(() -> service.queryDocumentDetail(dto))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("没有查看该文档的权限");
    }

    @Test
    @DisplayName("持 document:read-all 时列表不按 ACL 收窄")
    void pageWithReadAllDoesNotQueryVisibleIds() {
        RequestIdentity identity = new RequestIdentity(TENANT_ID, ADMIN_USER_ID, "admin", Set.of(1L),
            Set.of("document:read", "document:read-all"));
        TenantContext.setIdentity(identity);
        when(documentMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        DocumentPageQueryDto dto = new DocumentPageQueryDto();
        dto.setPageNo(1);
        dto.setPageSize(10);
        service.queryDocumentPage(dto);

        verify(documentAclStore, never()).visibleDocumentIdsForIdentity(any());
    }

    @Test
    @DisplayName("仅 document:read 时列表走 visibleDocumentIdsForIdentity")
    void pageWithoutReadAllUsesAcl() {
        authenticate(ADMIN_USER_ID);
        when(documentAclStore.visibleDocumentIdsForIdentity(any())).thenReturn(Set.of());

        DocumentPageQueryDto dto = new DocumentPageQueryDto();
        dto.setPageNo(1);
        dto.setPageSize(10);
        var page = service.queryDocumentPage(dto);

        assertThat(page.getTotal()).isZero();
        verify(documentAclStore).visibleDocumentIdsForIdentity(any());
        verify(documentMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("无 ACL 且无 document:read-all 时不能读解析产物")
    void parseArtifactsRequireAclWhenNoReadAll() {
        authenticate(ADMIN_USER_ID);
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(DOCUMENT_ID);
        document.setStatus(1);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
        when(documentAclStore.visibleDocumentIds(any(), any())).thenReturn(Set.of());

        DocumentParseArtifactQueryDto dto = new DocumentParseArtifactQueryDto();
        dto.setDocumentId(DOCUMENT_ID);

        assertThatThrownBy(() -> service.queryParseArtifacts(dto))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("没有查看该文档的权限");
    }

    @Test
    @DisplayName("上传成功后给上传者授予 MANAGE，使其始终可见自己的文档（入库即落 ACL）")
    void uploadGrantsManageToUploader() throws Exception {
        RequestIdentity identity = authenticate(ADMIN_USER_ID);
        SuperAgentKnowledgeBase knowledgeBase = new SuperAgentKnowledgeBase();
        knowledgeBase.setId(100L);
        knowledgeBase.setBaseName("默认知识库");
        when(knowledgeBaseManageService.requireEnabled(100L)).thenReturn(knowledgeBase);
        when(uidGenerator.getUid()).thenReturn(DOCUMENT_ID, DOCUMENT_ID + 1, DOCUMENT_ID + 2);
        StoredObjectInfo stored = new StoredObjectInfo();
        stored.setBucketName("bucket");
        stored.setObjectName("object");
        stored.setObjectUrl("http://minio/object");
        when(storageService.uploadOriginalFile(anyLong(), any(), any(), any())).thenReturn(stored);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
            ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));

        DocumentUploadDto dto = new DocumentUploadDto();
        dto.setKnowledgeBaseId("100");
        MockMultipartFile file = new MockMultipartFile("file", "sample.pdf", "application/pdf",
            "doc-content".getBytes(StandardCharsets.UTF_8));

        service.upload(file, dto);

        // 授权对象必须是"当前身份对应的 USER 主体"，等级 MANAGE：上传者对自己上传的文档始终可见。
        verify(documentAclStore).grant(eq(DOCUMENT_ID), eq(identity),
            eq(SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER), eq(ADMIN_USER_ID),
            eq(SuperAgentDocumentAcl.PERMISSION_MANAGE), eq(ADMIN_USER_ID));
    }

    private RequestIdentity authenticate(Long userId) {
        RequestIdentity identity = new RequestIdentity(TENANT_ID, userId, "admin", Set.of(1L),
            Set.of("document:upload", "document:delete", "document:write"));
        TenantContext.setIdentity(identity);
        return identity;
    }
}
