package org.smartledge.ai.chatagent.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线上评测 runner 的粒度归一化规则锁定：PARENT 展开为子 CHUNK、
 * KG_QUOTE 折算回 CHUNK、未知 identity 原样保留、去重保序。
 */
class LiveGoldReplayEvaluationNormalizationTest {

    private final Map<String, List<String>> parents = Map.of(
        "PARENT:7:88", List.of("CHUNK:7:100", "CHUNK:7:101"));
    private final Map<String, String> chunkDoc = Map.of(
        "555", "7");
    private final Map<String, List<String>> summaries = Map.of(
        "SUMMARY:RAPTOR:9", List.of("CHUNK:7:100", "CHUNK:7:777"));
    private final Map<String, String> kgEvidence = Map.of(
        "31", "555");

    @Test
    @DisplayName("PARENT 展开为子 CHUNK，命中任一子块即等价命中父块跨度")
    void expandsParentToChildChunks() {
        List<String> normalized = LiveGoldReplayEvaluation.normalizeIdentities(
            List.of("PARENT:7:88", "CHUNK:9:900"), parents, chunkDoc, summaries, kgEvidence);

        assertThat(normalized).containsExactly("CHUNK:7:100", "CHUNK:7:101", "CHUNK:9:900");
    }

    @Test
    @DisplayName("KG_QUOTE 证据折算回其 CHUNK identity")
    void foldsKgQuoteToChunk() {
        List<String> normalized = LiveGoldReplayEvaluation.normalizeIdentities(
            List.of("KG_QUOTE:31:CHUNK:555"), parents, chunkDoc, summaries, kgEvidence);

        assertThat(normalized).containsExactly("CHUNK:7:555");
    }

    @Test
    @DisplayName("映射缺失时原样保留；去重保序不过滤未知种类")
    void keepsUnknownAndDedupsInOrder() {
        List<String> normalized = LiveGoldReplayEvaluation.normalizeIdentities(
            java.util.Arrays.asList("CHUNK:7:100", "PARENT:9:999", "SUMMARY:KG:x", "CHUNK:7:100", null, ""),
            parents, chunkDoc, summaries, kgEvidence);

        assertThat(normalized).containsExactly("CHUNK:7:100", "PARENT:9:999", "SUMMARY:KG:x");
    }

    @Test
    @DisplayName("SUMMARY:RAPTOR 展开为其源 CHUNK（层级不同的摘要节点等价覆盖）")
    void expandsRaptorSummaryToSourceChunks() {
        List<String> normalized = LiveGoldReplayEvaluation.normalizeIdentities(
            List.of("SUMMARY:RAPTOR:9"), parents, chunkDoc, summaries, kgEvidence);

        assertThat(normalized).containsExactly("CHUNK:7:100", "CHUNK:7:777");
    }

    @Test
    @DisplayName("SUMMARY:KG 社区摘要折算回其锚定证据的 CHUNK")
    void foldsKgCommunitySummaryToChunk() {
        List<String> normalized = LiveGoldReplayEvaluation.normalizeIdentities(
            List.of("SUMMARY:KG:31"), parents, chunkDoc, summaries, kgEvidence);

        assertThat(normalized).containsExactly("CHUNK:7:555");
    }
}
