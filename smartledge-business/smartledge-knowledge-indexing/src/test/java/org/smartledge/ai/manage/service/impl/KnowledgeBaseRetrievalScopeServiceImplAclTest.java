package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.manage.data.SuperAgentKnowledgeBase;
import org.smartledge.ai.manage.model.DocumentMetadata;
import org.smartledge.ai.manage.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.manage.service.DocumentAclStore;
import org.smartledge.ai.manage.service.DocumentKnowledgeService;
import org.smartledge.ai.manage.service.DocumentMetadataStore;
import org.smartledge.ai.manage.service.KnowledgeBaseManageService;
import org.smartledge.ai.rag.runtime.KnowledgeBaseRuntimeConfigResolver;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 作用域门禁的可见性不变量测试。
 *
 * <p>对应 B3 要求「ACL 接入现有门禁、输出语义不变」与「越权必须 fail closed」。
 * 门禁是可见性**唯一**求值点：知识库元数据过滤决定"配置允许的范围"，
 * 文档 ACL 决定"这个身份在其中的可见范围"，两者只做交集。</p>
 *
 * <p>用假 ACL 仓储而不是 mock：这里要断言的是"门禁消费了什么身份、异常时收窄成什么"，
 * 输出集合的语义比调用次数更重要。</p>
 */
class KnowledgeBaseRetrievalScopeServiceImplAclTest {

    private static final Long TENANT_ID = 1L;

    private static final Long KB_ID = 100L;

    private static final Long DOC_ALLOWED = 1001L;

    private static final Long DOC_DENIED = 1002L;

    private final KnowledgeBaseManageService knowledgeBaseManageService = mock(KnowledgeBaseManageService.class);

    private final DocumentKnowledgeService documentKnowledgeService = mock(DocumentKnowledgeService.class);

    private final DocumentMetadataStore documentMetadataStore = mock(DocumentMetadataStore.class);

    private final KnowledgeBaseRuntimeConfigResolver runtimeConfigResolver =
        mock(KnowledgeBaseRuntimeConfigResolver.class);

    private final FakeDocumentAclStore documentAclStore = new FakeDocumentAclStore();

    private final KnowledgeBaseRetrievalScopeServiceImpl service = new KnowledgeBaseRetrievalScopeServiceImpl(
        knowledgeBaseManageService, documentKnowledgeService, runtimeConfigResolver,
        documentMetadataStore, documentAclStore);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("ACL 与知识库元数据过滤取交集：未授权的文档被剔除，输出结构不变")
    void aclNarrowsMetadataFilteredDocuments() {
        prepareCandidates(List.of(DOC_ALLOWED, DOC_DENIED));
        documentAclStore.visible = Set.of(DOC_ALLOWED);
        TenantContext.setIdentity(identity(3L, Set.of(3L)));

        var snapshot = resolveSelected();

        assertThat(snapshot.getAllowedDocumentIds()).containsExactly(DOC_ALLOWED);
        assertThat(snapshot.getAllowedTaskIds()).containsExactly(7001L);
        assertThat(snapshot.getAllowedDocuments()).hasSize(1);
    }

    @Test
    @DisplayName("ACL 只收窄不放宽：元数据过滤排除的文档即使被授权也不出现")
    void aclCannotWidenMetadataScope() {
        // 元数据过滤只放行 DOC_ALLOWED；ACL 对 DOC_ALLOWED 没有授权（但授权了 DOC_DENIED）。
        prepareCandidates(List.of(DOC_ALLOWED, DOC_DENIED), filterJson("allowed", "yes"),
            Map.of(DOC_ALLOWED, Map.of("allowed", "no"), DOC_DENIED, Map.of("allowed", "yes")));
        documentAclStore.visible = Set.of(DOC_DENIED);
        TenantContext.setIdentity(identity(3L, Set.of(3L)));

        var snapshot = resolveSelected();

        assertThat(snapshot.getAllowedDocumentIds()).isEmpty();
    }

    @Test
    @DisplayName("没有认证主体时可见集合为空（不回退到默认租户或全量放行）")
    void missingIdentityDeniesEverything() {
        prepareCandidates(List.of(DOC_ALLOWED, DOC_DENIED));
        documentAclStore.visible = Set.of(DOC_ALLOWED, DOC_DENIED);
        TenantContext.set(1L);

        var snapshot = resolveSelected();

        assertThat(snapshot.getAllowedDocumentIds()).isEmpty();
        assertThat(snapshot.getAllowedTaskIds()).isEmpty();
        assertThat(snapshot.getAllowedDocuments()).isEmpty();
    }

    @Test
    @DisplayName("ACL 解析异常时收窄为空集合，不放行任何文档")
    void aclFailureDeniesEverything() {
        prepareCandidates(List.of(DOC_ALLOWED, DOC_DENIED));
        documentAclStore.failure = new IllegalStateException("acl unavailable");
        TenantContext.setIdentity(identity(3L, Set.of(3L)));

        var snapshot = resolveSelected();

        assertThat(snapshot.getAllowedDocumentIds()).isEmpty();
    }

    @Test
    @DisplayName("门禁消费的是当前身份（租户 + 用户 + 角色），不是请求参数")
    void gateConsumesCurrentIdentity() {
        prepareCandidates(List.of(DOC_ALLOWED));
        documentAclStore.visible = Set.of(DOC_ALLOWED);
        RequestIdentity alice = identity(3L, Set.of(3L));
        TenantContext.setIdentity(alice);

        resolveSelected();

        assertThat(documentAclStore.lastIdentity).isEqualTo(alice);
        assertThat(documentAclStore.lastDocumentIds).containsExactly(DOC_ALLOWED);
    }

    private void prepareCandidates(List<Long> documentIds) {
        prepareCandidates(documentIds, null, Map.of());
    }

    private void prepareCandidates(List<Long> documentIds,
                                   String metadataFilterJson,
                                   Map<Long, Map<String, Object>> metadataByDocumentId) {
        SuperAgentKnowledgeBase base = new SuperAgentKnowledgeBase();
        base.setId(KB_ID);
        base.setBaseName("默认知识库");
        base.setMetadataFilterJson(metadataFilterJson);
        when(knowledgeBaseManageService.listEnabledByIds(any())).thenReturn(List.of(base));
        when(documentKnowledgeService.listRetrievableDocumentsByKnowledgeBaseIds(any())).thenReturn(documentIds.stream()
            .map(documentId -> new KnowledgeDocumentDescriptor(documentId, "文档-" + documentId,
                7001L + (documentId - DOC_ALLOWED), KB_ID, "默认知识库"))
            .toList());
        when(documentMetadataStore.loadByDocumentIds(any())).thenReturn(metadataByDocumentId.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                entry -> new DocumentMetadata(entry.getKey(), entry.getValue(), true))));
        when(runtimeConfigResolver.resolve(any())).thenReturn(null);
    }

    private String filterJson(String key, String value) {
        return "{\"version\":1,\"filter\":{\"and\":[{\"field\":\"" + key + "\",\"op\":\"=\",\"value\":\"" + value + "\"}]}}";
    }

    private org.smartledge.ai.manage.model.KnowledgeBaseSelectionSnapshot resolveSelected() {
        return service.resolve(ChatQueryMode.DOCUMENT, KnowledgeBaseSelectionMode.SELECTED, List.of(String.valueOf(KB_ID)));
    }

    private RequestIdentity identity(Long userId, Set<Long> roleIds) {
        return new RequestIdentity(TENANT_ID, userId, "user-" + userId, roleIds, Set.of("chat:use"));
    }

    /** 可控的 ACL 假实现：模拟"授权集合"与"读取失败"两种状态。 */
    private static final class FakeDocumentAclStore implements DocumentAclStore {

        private Set<Long> visible = Set.of();

        private RuntimeException failure;

        private RequestIdentity lastIdentity;

        private Collection<Long> lastDocumentIds = List.of();

        @Override
        public Set<Long> visibleDocumentIds(Collection<Long> documentIds, RequestIdentity identity) {
            return resolve(documentIds, identity);
        }

        @Override
        public Set<Long> writableDocumentIds(Collection<Long> documentIds, RequestIdentity identity) {
            return resolve(documentIds, identity);
        }

        @Override
        public Set<Long> manageableDocumentIds(Collection<Long> documentIds, RequestIdentity identity) {
            return resolve(documentIds, identity);
        }

        @Override
        public void grant(Long documentId, RequestIdentity identity, String principalType, Long principalId,
                          String permission, Long grantedBy) {
            throw new UnsupportedOperationException("门禁不写 ACL");
        }

        @Override
        public List<DocumentAclRecord> listByDocument(Long documentId, RequestIdentity identity) {
            throw new UnsupportedOperationException("门禁不列 ACL");
        }

        @Override
        public boolean revoke(Long documentId, String principalType, Long principalId, RequestIdentity identity) {
            throw new UnsupportedOperationException("门禁不改 ACL");
        }

        private Set<Long> resolve(Collection<Long> documentIds, RequestIdentity identity) {
            lastDocumentIds = List.copyOf(documentIds);
            lastIdentity = identity;
            if (failure != null) {
                throw failure;
            }
            return visible;
        }
    }

}
