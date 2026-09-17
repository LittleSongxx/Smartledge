package org.smartledge.ai.chatagent.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.rag.model.RankFeatureBundle;
import org.smartledge.ai.chatagent.rag.model.RetrievalChannelPlan;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.enums.RetrievalChannelEnum;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HybridFusionServiceInvariantTest {

    private static final double RRF_K = 60D;

    private final HybridFusionService fusion = new HybridFusionService(new RankFeatureService());

    @Test
    @DisplayName("同一 citation identity 只保留一条融合候选")
    void mergesSameCitationIdentity() {
        RetrievalDocument vector = chunk("a", 101L, 201L, 0.9D);
        RetrievalDocument keyword = chunk("a", 101L, 201L, 0.4D);
        List<RetrievalDocument> fused = fusion.fuse(List.of(
            new RetrievalChannelResult(RetrievalChannelEnum.VECTOR.getName(), List.of(vector)),
            new RetrievalChannelResult(RetrievalChannelEnum.KEYWORD.getName(), List.of(keyword))
        ), plan(8));

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.CHANNEL)).isEqualTo("hybrid");
        double expected = 2D / (RRF_K + 1D);
        assertThat(((Number) fused.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.HYBRID_SCORE)).doubleValue())
            .isCloseTo(expected, within(1e-9));
    }

    @Test
    @DisplayName("fusion 只截断 candidate window，不另做最终选择")
    void respectsCandidateWindowOnly() {
        List<RetrievalDocument> vector = List.of(
            chunk("a", 1L, 11L, 0.9D),
            chunk("b", 2L, 12L, 0.8D),
            chunk("c", 3L, 13L, 0.7D)
        );
        List<RetrievalDocument> fused = fusion.fuse(
            List.of(new RetrievalChannelResult(RetrievalChannelEnum.VECTOR.getName(), vector)),
            plan(2)
        );
        assertThat(fused).hasSize(2);
    }

    @Test
    @DisplayName("单通道一条时 fusion 分等于加权 RRF，不被 max 归一原始分抬满")
    void singleHitDoesNotInflateByNormalizedOriginalScore() {
        RetrievalDocument only = chunk("low", 9L, 99L, 0.05D);
        List<RetrievalDocument> fused = fusion.fuse(
            List.of(new RetrievalChannelResult(RetrievalChannelEnum.VECTOR.getName(), List.of(only))),
            plan(4)
        );
        assertThat(fused).hasSize(1);
        double expectedRrf = 1D / (RRF_K + 1D);
        assertThat(((Number) fused.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.HYBRID_SCORE)).doubleValue())
            .isCloseTo(expectedRrf, within(1e-9));
        assertThat(((Number) fused.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.RRF_SCORE)).doubleValue())
            .isCloseTo(expectedRrf, within(1e-9));
        assertThat(((Number) fused.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.VECTOR_SCORE)).doubleValue())
            .isEqualTo(0.05D);
    }

    private RetrievalPlan plan(int window) {
        return RetrievalPlan.builder()
            .candidateWindow(window)
            .rankFeatures(RankFeatureBundle.builder()
                .enabledFeatures(List.of("CHANNEL_RRF"))
                .rankWeight(1D)
                .originalScoreWeight(0.08D)
                .metadataBoostWeight(0.04D)
                .maxMetadataBoost(1D)
                .build())
            .channels(List.of(
                RetrievalChannelPlan.builder().channelName(RetrievalChannelEnum.VECTOR.getName()).enabled(true).topK(8).timeoutMs(1).budget(8).weight(1D).build(),
                RetrievalChannelPlan.builder().channelName(RetrievalChannelEnum.KEYWORD.getName()).enabled(true).topK(8).timeoutMs(1).budget(8).weight(1D).build()
            ))
            .build();
    }

    private RetrievalDocument chunk(String id, long documentId, long chunkId, double score) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, documentId);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, chunkId);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TEXT");
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        return RetrievalDocument.builder().id(id).text("正文 " + id).metadata(metadata).score(score).build();
    }
}
