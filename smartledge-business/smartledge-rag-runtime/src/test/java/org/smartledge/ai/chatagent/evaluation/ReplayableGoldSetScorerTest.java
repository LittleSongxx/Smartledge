package org.smartledge.ai.chatagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReplayableGoldSetScorerTest {

    @Test
    @DisplayName("默认金标至少 15 条，空 scope 不进 mean Recall")
    void scoresDefaultGoldSet() {
        ReplayableGoldSetScorer scorer = new ReplayableGoldSetScorer(new ObjectMapper());
        ReplayableGoldSet set = scorer.loadDefault();
        assertThat(set.schemaVersion()).isEqualTo("rag-gold.v1");
        assertThat(set.cases()).hasSizeGreaterThanOrEqualTo(15);

        Map<String, List<String>> retrieved = new HashMap<>();
        for (ReplayableGoldSet.GoldCase goldCase : set.cases()) {
            if (goldCase.relevantIdentities().isEmpty()) {
                retrieved.put(goldCase.id(), List.of());
            } else {
                retrieved.put(goldCase.id(), List.of(goldCase.relevantIdentities().get(0)));
            }
        }
        retrieved.put("gold-reimburse-receipt", List.of("CHUNK:102:302"));
        retrieved.put("gold-empty-scope", List.of());

        List<ReplayableGoldSetScorer.GoldScore> scores = scorer.score(set, goldCase -> retrieved.get(goldCase.id()));
        ReplayableGoldSetScorer.GoldScore leave = scores.stream()
            .filter(score -> "gold-leave-window".equals(score.caseId()))
            .findFirst()
            .orElseThrow();
        ReplayableGoldSetScorer.GoldScore empty = scores.stream()
            .filter(score -> "gold-empty-scope".equals(score.caseId()))
            .findFirst()
            .orElseThrow();
        assertThat(leave.recallAtK()).isEqualTo(1D);
        assertThat(leave.ndcgAtK()).isEqualTo(1D);
        assertThat(empty.recallAtK()).isZero();
        assertThat(empty.emptyScopeCorrectness()).isEqualTo(1D);
        assertThat(scorer.emptyScopeCorrectness(scores)).isEqualTo(1D);
        // 14 条非空 scope：11 条只召回第一条相关 identity=1.0；
        // gold-reimburse-receipt / gold-seal-rule / gold-multi-doc 各 2 条相关只召回 1 条=0.5。
        assertThat(scorer.meanRecall(scores)).isCloseTo(12.5D / 14D, within(1e-9));
        assertThat(scorer.meanNdcg(scores)).isGreaterThan(0D);
        assertThat(scorer.meanMrr(scores)).isGreaterThan(0D);
        assertThat(scorer.meanMrr(scores)).isLessThanOrEqualTo(1D);
    }

    @Test
    @DisplayName("窗口外的相关 identity 不计 Recall@K")
    void ignoresHitsOutsideK() {
        ReplayableGoldSetScorer scorer = new ReplayableGoldSetScorer(new ObjectMapper());
        ReplayableGoldSet.GoldCase goldCase = new ReplayableGoldSet.GoldCase(
            "window",
            "q",
            List.of("CHUNK:1:1"),
            1
        );
        ReplayableGoldSetScorer.GoldScore score = scorer.scoreCase(goldCase, List.of("CHUNK:9:9", "CHUNK:1:1"));
        assertThat(score.recallAtK()).isZero();
        assertThat(score.ndcgAtK()).isZero();
        assertThat(score.mrr()).isZero();
    }
}
