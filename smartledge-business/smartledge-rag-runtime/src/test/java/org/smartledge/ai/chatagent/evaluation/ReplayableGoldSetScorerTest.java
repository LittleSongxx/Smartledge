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
            ReplayableGoldSet.GoldCase.CASE_TYPE_RANKING,
            1
        );
        ReplayableGoldSetScorer.GoldScore score = scorer.scoreCase(goldCase, List.of("CHUNK:9:9", "CHUNK:1:1"));
        assertThat(score.recallAtK()).isZero();
        assertThat(score.ndcgAtK()).isZero();
        assertThat(score.mrr()).isZero();
    }

    @Test
    @DisplayName("重复 identity 只按首次出现位次计一次，nDCG 不因重复突破 1")
    void duplicateIdentitiesCountOnce() {
        ReplayableGoldSetScorer scorer = new ReplayableGoldSetScorer(new ObjectMapper());
        // 修复前：relevant={A}、retrieved=[A,A] 会让 DCG 累加两次，nDCG≈1.63 > 1。
        ReplayableGoldSet.GoldCase goldCase = new ReplayableGoldSet.GoldCase(
            "dup",
            "q",
            List.of("CHUNK:1:1"),
            ReplayableGoldSet.GoldCase.CASE_TYPE_RANKING,
            3
        );
        ReplayableGoldSetScorer.GoldScore duplicated = scorer.scoreCase(goldCase,
            List.of("CHUNK:1:1", "CHUNK:1:1", "CHUNK:9:9"));
        assertThat(duplicated.ndcgAtK()).isCloseTo(1D, within(1e-9));
        assertThat(duplicated.recallAtK()).isEqualTo(1D);
        assertThat(duplicated.mrr()).isEqualTo(1D);

        // 重复项不挤占窗口：有效排序按去重后的唯一位次解释，[A,A,B] 即 [A,B]，
        // K=2 时 B 是第 2 个唯一位次，仍在窗口内（recall=1.0，而非按原始位置的 0.5）。
        ReplayableGoldSet.GoldCase twoRelevant = new ReplayableGoldSet.GoldCase(
            "dup-window",
            "q",
            List.of("CHUNK:1:1", "CHUNK:2:2"),
            ReplayableGoldSet.GoldCase.CASE_TYPE_RANKING,
            2
        );
        ReplayableGoldSetScorer.GoldScore windowScore = scorer.scoreCase(twoRelevant,
            List.of("CHUNK:1:1", "CHUNK:1:1", "CHUNK:2:2"));
        assertThat(windowScore.recallAtK()).isCloseTo(1D, within(1e-9));
    }

    @Test
    @DisplayName("空 relevantIdentities 不再隐式等于空范围：装载与打分都要求显式 caseType")
    void emptyRelevantRequiresExplicitEmptyScopeType() {
        ReplayableGoldSetScorer scorer = new ReplayableGoldSetScorer(new ObjectMapper());

        // 未标记的空相关 RANKING case：score 入口直接拒绝。
        ReplayableGoldSet unlabeled = new ReplayableGoldSet("rag-gold.v1", "", List.of(
            new ReplayableGoldSet.GoldCase("unlabeled", "q", List.of(), null, 5)
        ));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scorer.score(unlabeled, any -> List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EMPTY_SCOPE")
            .hasMessageContaining("unlabeled");

        // 显式空范围 case：行为与原来一致（检索为空=正确，检索出内容=错误）。
        ReplayableGoldSet emptyScope = new ReplayableGoldSet("rag-gold.v1", "", List.of(
            new ReplayableGoldSet.GoldCase("empty", "q", List.of(),
                ReplayableGoldSet.GoldCase.CASE_TYPE_EMPTY_SCOPE, 5)
        ));
        List<ReplayableGoldSetScorer.GoldScore> scores = scorer.score(emptyScope, any -> List.of());
        assertThat(scores.get(0).emptyScopeCorrectness()).isEqualTo(1D);
        List<ReplayableGoldSetScorer.GoldScore> leaked = scorer.score(emptyScope, any -> List.of("CHUNK:1:1"));
        assertThat(leaked.get(0).emptyScopeCorrectness()).isZero();

        // EMPTY_SCOPE 却携带相关集：同样拒绝，两个方向都不留模糊地带。
        ReplayableGoldSet contradictory = new ReplayableGoldSet("rag-gold.v1", "", List.of(
            new ReplayableGoldSet.GoldCase("bad", "q", List.of("CHUNK:1:1"),
                ReplayableGoldSet.GoldCase.CASE_TYPE_EMPTY_SCOPE, 5)
        ));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scorer.score(contradictory, any -> List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bad");

        // 装载路径同样拦截：JSON 里的未标记空相关 case 在 load 时失败。
        String json = "{\"schemaVersion\":\"rag-gold.v1\",\"cases\":[{\"id\":\"x\",\"query\":\"q\","
            + "\"relevantIdentities\":[],\"k\":5}]}";
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> scorer.load(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .hasMessageContaining("x");
    }
}
