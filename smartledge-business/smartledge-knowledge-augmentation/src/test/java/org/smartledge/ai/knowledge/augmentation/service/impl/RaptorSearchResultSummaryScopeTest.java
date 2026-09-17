package org.smartledge.ai.knowledge.augmentation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentRaptorNode;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentRaptorNodeMapper;
import org.smartledge.ai.knowledge.augmentation.model.raptor.RaptorSearchResult;
import org.smartledge.ai.knowledge.augmentation.service.RaptorSummaryIndexService;
import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
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
 * RAPTOR「源 chunk 命中」路径的摘要可见性测试。
 *
 * <p>承接 B1-1 / S21-E：那一轮只堵住了"没有可见源 chunk"的仅摘要回退，
 * 而有可见源 chunk 时仍把**整段跨文档聚合摘要**挂到结果上（{@code toResult} 直接取
 * {@code node.getSummary()}）。本测试锁定：可见范围不覆盖节点全部来源文档时，
 * chunk 原文照常返回，但聚合摘要与节点标题必须被丢弃。</p>
 */
@ExtendWith(MockitoExtension.class)
class RaptorSearchResultSummaryScopeTest {

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

    @Test
    @DisplayName("可见范围覆盖全部来源文档时，源 chunk 结果携带聚合摘要")
    void summaryKeptWhenWholeScopeVisible() {
        SuperAgentRaptorNode node = datasetNode("[" + DOC_A + "," + DOC_B + "]");

        RaptorSearchResult result = service.toResult(node, chunk(DOC_A), 0.8D, 0D, true);

        assertThat(result.getRaptorSummary()).isEqualTo("跨文档聚合摘要");
        assertThat(result.getRaptorNodeTitle()).isEqualTo("数据集汇总");
    }

    @Test
    @DisplayName("可见范围只覆盖部分来源文档时，源 chunk 结果不携带聚合摘要与节点标题")
    void summaryDroppedWhenScopeNarrowed() {
        SuperAgentRaptorNode node = datasetNode("[" + DOC_A + "," + DOC_B + "]");

        assertThat(service.summaryScopeFullyVisible(node, Set.of(DOC_A, DOC_B))).isTrue();
        assertThat(service.summaryScopeFullyVisible(node, Set.of(DOC_A))).isFalse();

        RaptorSearchResult result = service.toResult(node, chunk(DOC_A), 0.8D, 0D, false);

        // 证据仍然是可见文档里的 chunk 原文，但聚合摘要不得出现。
        assertThat(result.getChunkText()).isEqualTo("可见文档的正文片段");
        assertThat(result.getRaptorSummary()).isNull();
        assertThat(result.getRaptorNodeTitle()).isNull();
        assertThat(result.getRaptorNodeId()).isEqualTo(node.getId());
    }

    @Test
    @DisplayName("节点没有来源文档记录时不视为聚合摘要（由主键判定继续约束）")
    void nodeWithoutSourceDocumentsIsNotTreatedAsAggregate() {
        SuperAgentRaptorNode node = datasetNode(null);
        assertThat(service.summaryScopeFullyVisible(node, Set.of(DOC_A))).isTrue();
    }

    private SuperAgentRaptorNode datasetNode(String sourceDocumentIdsJson) {
        SuperAgentRaptorNode node = new SuperAgentRaptorNode();
        node.setId(9001L);
        node.setDocumentId(0L);
        node.setTaskId(0L);
        node.setNodeLevel(2);
        node.setTitle("数据集汇总");
        node.setSummary("跨文档聚合摘要");
        node.setSourceDocumentIdsJson(sourceDocumentIdsJson);
        node.setSourceTaskIdsJson("[" + TASK + "]");
        return node;
    }

    private SuperAgentDocumentChunk chunk(long documentId) {
        SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
        chunk.setId(7001L);
        chunk.setDocumentId(documentId);
        chunk.setTaskId(TASK);
        chunk.setChunkNo(1);
        chunk.setChunkText("可见文档的正文片段");
        chunk.setTitle("小节标题");
        return chunk;
    }
}
