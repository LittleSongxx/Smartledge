package org.smartledge.ai.knowledge.augmentation.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCanonicalEntityGroupMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCanonicalEntityMemberMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCrossDocumentCommunityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCrossDocumentCommunityMemberMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEntityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEvidenceMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationGroupMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationGroupMemberMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationMapper;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCrossDocumentIndex;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCrossDocumentIndexSupport;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * GraphRAG 跨文档社区的可见性判定测试。
 *
 * <p>跨文档社区是数据集级聚合对象：标题、摘要、证据都由**全部来源文档**生成，没有可按来源裁剪的
 * 原文回退路径。因此判定必须是文档维度 ALL：可见范围不覆盖全部来源文档时整条社区不进入候选。
 * 原实现只在"与可见文档有交集"时入场（relation group 命中即入场），会把范围外文档的内容
 * 带进社区摘要。</p>
 */
class GraphRagCrossDocumentCommunityVisibilityTest {

    private static final long DOC_A = 1001L;
    private static final long DOC_B = 1002L;

    @Test
    @DisplayName("来源文档全部可见时社区可见")
    void visibleWhenWholeScopeCovered() {
        assertThat(visible(Set.of(DOC_A, DOC_B), Set.of(DOC_A, DOC_B))).isTrue();
        assertThat(visible(Set.of(DOC_A, DOC_B), Set.of(DOC_A, DOC_B, 1003L))).isTrue();
    }

    @Test
    @DisplayName("来源文档只有部分可见时社区不可见（any 语义泄漏点）")
    void hiddenWhenScopeNarrowed() {
        assertThat(visible(Set.of(DOC_A, DOC_B), Set.of(DOC_A))).isFalse();
        assertThat(visible(Set.of(DOC_A, DOC_B), Set.of(1003L))).isFalse();
    }

    @Test
    @DisplayName("社区没有来源文档记录时按不可见处理（无法证明来源，收窄而非放行）")
    void hiddenWhenSourceDocumentsMissing() {
        assertThat(visible(Set.of(), Set.of(DOC_A))).isFalse();
        assertThat(visible(null, Set.of(DOC_A))).isFalse();
    }

    @Test
    @DisplayName("可见集合为空时任何社区都不可见")
    void hiddenWhenNoAllowedDocuments() {
        assertThat(visible(Set.of(DOC_A), Set.of())).isFalse();
        assertThat(visible(Set.of(DOC_A), null)).isFalse();
    }

    private boolean visible(Set<Long> communityDocumentIds, Set<Long> allowedDocumentIds) {
        return new GraphRagCrossDocumentIndexServiceImplRuleProbe()
            .visible(communityDocumentIds, allowedDocumentIds);
    }

    /**
     * 直接复用生产实现的判定方法：把实例构造隔离在探针里，避免测试关心 15 个协作者。
     */
    private static final class GraphRagCrossDocumentIndexServiceImplRuleProbe {

        private final GraphRagCrossDocumentIndexServiceImpl probe;

        private GraphRagCrossDocumentIndexServiceImplRuleProbe() {
            // 只测判定规则本身：协作者全部是 mock，判定不依赖它们的返回值。
            this.probe = new GraphRagCrossDocumentIndexServiceImpl(
                mock(SuperAgentDocumentMapper.class),
                mock(SuperAgentKgEntityMapper.class),
                mock(SuperAgentKgRelationMapper.class),
                mock(SuperAgentKgEvidenceMapper.class),
                mock(SuperAgentKgCanonicalEntityGroupMapper.class),
                mock(SuperAgentKgCanonicalEntityMemberMapper.class),
                mock(SuperAgentKgRelationGroupMapper.class),
                mock(SuperAgentKgRelationGroupMemberMapper.class),
                mock(SuperAgentKgCrossDocumentCommunityMapper.class),
                mock(SuperAgentKgCrossDocumentCommunityMemberMapper.class),
                mock(SuperAgentKnowledgeTopicNodeMapper.class),
                mock(SuperAgentTopicDocumentRelationMapper.class),
                mock(GraphRagCrossDocumentIndexSupport.class),
                mock(UidGenerator.class),
                new ObjectMapper());
        }

        boolean visible(Set<Long> communityDocumentIds, Set<Long> allowedDocumentIds) {
            GraphRagCrossDocumentIndex.CrossDocumentCommunity community =
                new GraphRagCrossDocumentIndex.CrossDocumentCommunity(
                    1L, "community-1", "社区标题", "社区摘要",
                    Set.of(), Set.of(), Set.of(), communityDocumentIds, null, 0D, null, null);
            return probe.communityScopeFullyVisible(community, allowedDocumentIds);
        }
    }
}
