package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.manage.data.SuperAgentKnowledgeRouteTrace;
import org.smartledge.ai.manage.dto.DocumentProfileRegenerateDto;
import org.smartledge.ai.manage.dto.KnowledgeRouteTraceQueryDto;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeRouteTraceMapper;
import org.smartledge.ai.manage.vo.KnowledgeRouteTracePageVo;
import org.smartledge.ai.manage.service.DocumentAclStore;
import org.smartledge.ai.manage.service.DocumentProfileService;
import org.smartledge.ai.manage.support.MybatisLambdaCacheTestSupport;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.exception.SuperAgentFrameException;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeManageServiceImplAclTest {

    private static final Long DOCUMENT_ID = 88L;

    @Mock
    private DocumentAclStore documentAclStore;

    @Mock
    private DocumentProfileService documentProfileService;

    @Mock
    private SuperAgentKnowledgeRouteTraceMapper knowledgeRouteTraceMapper;

    @InjectMocks
    private KnowledgeManageServiceImpl service;

    @BeforeAll
    static void initMybatisMetadata() {
        MybatisLambdaCacheTestSupport.initialize(SuperAgentKnowledgeRouteTrace.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("无 WRITE ACL 时不能重算画像")
    void regenerateRequiresWrite() {
        TenantContext.setIdentity(new RequestIdentity(1L, 1L, "admin", Set.of(1L),
            Set.of("document:read-all", "document:write")));
        when(documentAclStore.writableDocumentIds(any(), any())).thenReturn(Set.of());

        DocumentProfileRegenerateDto dto = new DocumentProfileRegenerateDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));

        assertThatThrownBy(() -> service.regenerateProfile(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("没有修改该文档的权限");
        verify(documentProfileService, never()).regenerateProfile(any());
    }

    @Test
    @DisplayName("有 WRITE ACL 时进入重算")
    void regeneratePassesWithWrite() {
        TenantContext.setIdentity(new RequestIdentity(1L, 1L, "admin", Set.of(1L), Set.of("document:write")));
        when(documentAclStore.writableDocumentIds(eq(List.of(DOCUMENT_ID)), any())).thenReturn(Set.of(DOCUMENT_ID));
        when(documentProfileService.regenerateProfile(DOCUMENT_ID)).thenThrow(new SuperAgentFrameException(400, "画像不存在"));

        DocumentProfileRegenerateDto dto = new DocumentProfileRegenerateDto();
        dto.setDocumentId(String.valueOf(DOCUMENT_ID));

        assertThatThrownBy(() -> service.regenerateProfile(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("画像不存在");
        verify(documentProfileService).regenerateProfile(DOCUMENT_ID);
    }

    @Test
    @DisplayName("管理端路由追踪允许按租户列出，不强制会话")
    void routeTraceAllowsTenantScopedListWithoutConversation() {
        TenantContext.set(1L);
        KnowledgeRouteTraceQueryDto dto = new KnowledgeRouteTraceQueryDto();
        dto.setPageNo("1");
        dto.setPageSize("20");
        when(knowledgeRouteTraceMapper.selectCount(any())).thenReturn(0L);
        when(knowledgeRouteTraceMapper.selectList(any())).thenReturn(List.of());

        KnowledgeRouteTracePageVo page = service.queryRouteTracePage(dto);

        assertThat(page.getTotalSize()).isEqualTo("0");
        verify(knowledgeRouteTraceMapper).selectCount(any());
    }

    @Test
    @DisplayName("管理端路由追踪缺少租户上下文时拒绝")
    void routeTraceRequiresTenant() {
        KnowledgeRouteTraceQueryDto dto = new KnowledgeRouteTraceQueryDto();
        dto.setConversationId("owned-session");

        assertThatThrownBy(() -> service.queryRouteTracePage(dto))
            .isInstanceOf(SuperAgentFrameException.class)
            .hasMessageContaining("缺少租户上下文");
        verify(knowledgeRouteTraceMapper, never()).selectCount(any());
    }
}
