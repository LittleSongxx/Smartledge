package org.smartledge.ai.knowledge.augmentation.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCanonicalEntityGroup;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCanonicalEntityMember;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCrossDocumentCommunity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCrossDocumentCommunityMember;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEntity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEvidence;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelation;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelationGroup;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelationGroupMember;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCanonicalEntityGroupMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCanonicalEntityMemberMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCrossDocumentCommunityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCrossDocumentCommunityMemberMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEntityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEvidenceMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationGroupMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationGroupMemberMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationMapper;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCrossDocumentIndexSupport;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.smartledge.ai.manage.support.DerivedRowTenantScope;
import org.smartledge.database.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跨文档投影重建的租户作用域测试（S22 批次 2）。
 *
 * <p>构建链路的按文档阶段现在跑在父文档租户作用域内，而跨文档投影是**全局重建**：它读全库活跃文档
 * （{@code selectActiveForGraphProjection}）、整表重写派生索引。如果它继承调用方的租户作用域，
 * 租户拦截器会把"活跃文档"收窄成本租户，跨租户的派生索引会静默只剩一个租户 —— 不报错、不失败，
 * 只是别的租户的实体关系再也召不回来。</p>
 *
 * <p>因此这里锁定：即使从租户作用域内部调用，跨文档重建看到的仍然是<b>系统上下文</b>。</p>
 */
class GraphRagCrossDocumentTenantScopeTest {

    private final SuperAgentDocumentMapper documentMapper = mock(SuperAgentDocumentMapper.class);

    /**
     * LambdaQueryWrapper 需要实体的 TableInfo 缓存（平时由映射器扫描建立）。
     * 这里就地初始化，因为 knowledge-indexing 的测试支持类不在本模块的测试 classpath 上。
     */
    @BeforeAll
    static void initMybatisMetadata() {
        for (Class<?> entity : List.of(SuperAgentKgCrossDocumentCommunityMember.class,
                SuperAgentKgCrossDocumentCommunity.class, SuperAgentKgRelationGroupMember.class,
                SuperAgentKgRelationGroup.class, SuperAgentKgCanonicalEntityMember.class,
                SuperAgentKgCanonicalEntityGroup.class, SuperAgentKgEntity.class, SuperAgentKgRelation.class,
                SuperAgentKgEvidence.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("从租户作用域内调用跨文档重建，活跃文档读取仍然在系统上下文")
    void crossDocumentRebuildAlwaysReadsAcrossTenants() {
        AtomicReference<Long> tenantSeenByActiveDocumentQuery = new AtomicReference<>();
        when(documentMapper.selectActiveForGraphProjection()).thenAnswer(invocation -> {
            tenantSeenByActiveDocumentQuery.set(TenantContext.get());
            return List.of();
        });
        GraphRagCrossDocumentIndexServiceImpl service = service();

        AtomicReference<Long> tenantAfterCrossDocumentCall = new AtomicReference<>();
        TenantContext.runWith(9L, () -> {
            service.rebuildAll(1L, 2L);
            // 跨租户重建退出后必须恢复调用方（按文档）的租户作用域，而不是留下系统上下文。
            tenantAfterCrossDocumentCall.set(TenantContext.get());
        });

        assertThat(tenantSeenByActiveDocumentQuery.get()).isEqualTo(TenantContext.SYSTEM);
        assertThat(tenantAfterCrossDocumentCall.get()).isEqualTo(9L);
        assertThat(TenantContext.isPresent()).isFalse();
    }

    @Test
    @DisplayName("全量重建入口同样声明系统上下文")
    void fullRebuildDeclaresSystemContext() {
        AtomicReference<Long> tenantSeenByActiveDocumentQuery = new AtomicReference<>();
        when(documentMapper.selectActiveForGraphProjection()).thenAnswer(invocation -> {
            tenantSeenByActiveDocumentQuery.set(TenantContext.get());
            return List.of();
        });

        DerivedRowTenantScope.runPerDocument(9L, () -> service().rebuildAll());

        assertThat(tenantSeenByActiveDocumentQuery.get()).isEqualTo(TenantContext.SYSTEM);
    }

    private GraphRagCrossDocumentIndexServiceImpl service() {
        return new GraphRagCrossDocumentIndexServiceImpl(documentMapper, mock(SuperAgentKgEntityMapper.class),
                mock(SuperAgentKgRelationMapper.class), mock(SuperAgentKgEvidenceMapper.class),
                mock(SuperAgentKgCanonicalEntityGroupMapper.class),
                mock(SuperAgentKgCanonicalEntityMemberMapper.class), mock(SuperAgentKgRelationGroupMapper.class),
                mock(SuperAgentKgRelationGroupMemberMapper.class),
                mock(SuperAgentKgCrossDocumentCommunityMapper.class),
                mock(SuperAgentKgCrossDocumentCommunityMemberMapper.class),
                mock(SuperAgentKnowledgeTopicNodeMapper.class), mock(SuperAgentTopicDocumentRelationMapper.class),
                mock(GraphRagCrossDocumentIndexSupport.class), mock(UidGenerator.class), new ObjectMapper());
    }
}
