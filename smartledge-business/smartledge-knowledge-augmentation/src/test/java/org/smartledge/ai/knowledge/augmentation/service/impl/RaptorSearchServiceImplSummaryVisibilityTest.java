package org.smartledge.ai.knowledge.augmentation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentRaptorNode;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentRaptorNodeMapper;
import org.smartledge.ai.knowledge.augmentation.service.RaptorSummaryIndexService;
import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.support.PgVectorTenantOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAPTOR 摘要可见性不变量测试。
 *
 * <p>对应缺陷 S21-E：dataset 级汇总节点的摘要文本是对其**全部来源文档**聚合生成的，
 * 事后无法按来源裁剪。原实现用 any 语义判断（只要有一个来源文档可见即返回整段摘要），
 * 会把可见范围之外文档的内容带进检索结果，违反 hard scope 不变量。</p>
 */
@ExtendWith(MockitoExtension.class)
class RaptorSearchServiceImplSummaryVisibilityTest {

    private static final long DOC_A = 1001L;
    private static final long DOC_B = 1002L;
    private static final long TASK = 2001L;

    @Mock
    private JdbcTemplate pgVectorJdbcTemplate;

    @Mock
    private ObjectProvider<EmbeddingPort> embeddingModelProvider;

    @Mock
    private SuperAgentRaptorNodeMapper raptorNodeMapper;

    @Mock
    private SuperAgentDocumentMapper documentMapper;

    @Mock
    private SuperAgentDocumentChunkMapper chunkMapper;

    @Mock
    private ObjectProvider<RaptorSummaryIndexService> raptorSummaryIndexServiceProvider;

    @Mock
    private PgVectorTenantOperations pgVectorOperations;

    private RaptorSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RaptorSearchServiceImpl(pgVectorJdbcTemplate, pgVectorOperations, embeddingModelProvider,
            raptorNodeMapper, documentMapper, chunkMapper, new ObjectMapper(), raptorSummaryIndexServiceProvider);
    }

    /** dataset 级汇总节点：跨文档，document_id 是 0 哨兵，来源文档由 source_document_ids_json 承载。 */
    private SuperAgentRaptorNode datasetNodeCovering(String sourceDocumentIdsJson) {
        SuperAgentRaptorNode node = new SuperAgentRaptorNode();
        node.setId(9001L);
        node.setDocumentId(0L);
        node.setTaskId(0L);
        node.setNodeLevel(2);
        node.setTitle("数据集汇总");
        node.setSummary("这段摘要由全部来源文档聚合生成，包含了 A 与 B 的内容。");
        node.setSourceDocumentIdsJson(sourceDocumentIdsJson);
        node.setSourceTaskIdsJson("[" + TASK + "]");
        node.setSourceChunkIdsJson("[7001,7002]");
        node.setSourceParentBlockIdsJson("[8001]");
        return node;
    }

    @Test
    @DisplayName("可见集合覆盖全部来源文档时，dataset 汇总正常返回")
    void datasetSummaryReturnedWhenWholeScopeVisible() {
        SuperAgentRaptorNode node = datasetNodeCovering("[" + DOC_A + "," + DOC_B + "]");

        java.util.List<?> result = java.util.List.of(service.toSummaryOnlyResult(node, 0.9D,
            Set.of(DOC_A, DOC_B), Set.of(TASK)));

        assertThat(result.get(0)).isNotNull();
    }

    @Test
    @DisplayName("可见集合只覆盖部分来源文档时，dataset 汇总必须被拒绝（any 语义泄漏点）")
    void datasetSummaryRejectedWhenScopeNarrowed() {
        SuperAgentRaptorNode node = datasetNodeCovering("[" + DOC_A + "," + DOC_B + "]");

        // 只看得到 A，但摘要里含有 B 的内容 —— 必须返回 null 而不是带出整段摘要
        assertThat(service.toSummaryOnlyResult(node, 0.9D, Set.of(DOC_A), Set.of(TASK))).isNull();
    }

    @Test
    @DisplayName("可见集合与来源文档完全不相交时被拒绝")
    void datasetSummaryRejectedWhenNoOverlap() {
        SuperAgentRaptorNode node = datasetNodeCovering("[" + DOC_A + "," + DOC_B + "]");

        assertThat(service.toSummaryOnlyResult(node, 0.9D, Set.of(3003L), Set.of(TASK))).isNull();
    }

    @Test
    @DisplayName("文档级节点未声明来源文档时退回主键判定，行为不变")
    void documentLevelNodeFallsBackToPrimaryId() {
        SuperAgentRaptorNode node = new SuperAgentRaptorNode();
        node.setId(9100L);
        node.setDocumentId(DOC_A);
        node.setTaskId(TASK);
        node.setNodeLevel(1);
        node.setTitle("文档级摘要");
        node.setSummary("仅来自文档 A。");
        node.setSourceDocumentIdsJson(null);
        node.setSourceTaskIdsJson(null);

        assertThat(service.toSummaryOnlyResult(node, 0.8D, Set.of(DOC_A), Set.of(TASK))).isNotNull();
        assertThat(service.toSummaryOnlyResult(node, 0.8D, Set.of(DOC_B), Set.of(TASK))).isNull();
    }

    @Test
    @DisplayName("任务维度是新鲜度判定：来源索引版本已全部过期时不再返回")
    void staleTaskVersionIsRejected() {
        SuperAgentRaptorNode node = datasetNodeCovering("[" + DOC_A + "]");
        node.setSourceTaskIdsJson("[9999]");

        // 文档全部可见，但该汇总只由已过期的索引版本生成 —— 作为陈旧产物拒绝
        assertThat(service.toSummaryOnlyResult(node, 0.7D, Set.of(DOC_A), Set.of(TASK))).isNull();
    }

    @Test
    @DisplayName("任务维度用 ANY 语义：跨版本汇总只要有一个版本仍当前即可返回")
    void anyCurrentTaskVersionKeepsSummary() {
        SuperAgentRaptorNode node = datasetNodeCovering("[" + DOC_A + "]");
        node.setSourceTaskIdsJson("[" + TASK + ",9999]");

        assertThat(service.toSummaryOnlyResult(node, 0.7D, Set.of(DOC_A), Set.of(TASK))).isNotNull();
    }
}
