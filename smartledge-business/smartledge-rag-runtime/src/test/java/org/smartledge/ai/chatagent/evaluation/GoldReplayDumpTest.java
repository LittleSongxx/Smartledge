package org.smartledge.ai.chatagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeResult;
import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeSubQuestion;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class GoldReplayDumpTest {

    @Test
    @DisplayName("replay dump 按 case id 计分，未知 case 失败关闭")
    void scoresReplayDumpAndRejectsUnknownCase() throws Exception {
        ReplayableGoldSetScorer scorer = new ReplayableGoldSetScorer(new ObjectMapper());
        ReplayableGoldSet set = scorer.loadDefault();
        String json = """
            {
              "schemaVersion": "rag-gold-replay.v1",
              "description": "unit replay",
              "retrieved": {
                "gold-leave-window": ["CHUNK:101:201"],
                "gold-empty-scope": []
              }
            }
            """;
        GoldReplayDump dump = scorer.loadReplay(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        List<ReplayableGoldSetScorer.GoldScore> scores = scorer.score(set, dump);
        ReplayableGoldSetScorer.GoldScore leave = scores.stream()
            .filter(score -> "gold-leave-window".equals(score.caseId()))
            .findFirst()
            .orElseThrow();
        ReplayableGoldSetScorer.GoldScore empty = scores.stream()
            .filter(score -> "gold-empty-scope".equals(score.caseId()))
            .findFirst()
            .orElseThrow();
        ReplayableGoldSetScorer.GoldScore missing = scores.stream()
            .filter(score -> "gold-invoice-title".equals(score.caseId()))
            .findFirst()
            .orElseThrow();
        assertThat(leave.recallAtK()).isEqualTo(1D);
        assertThat(leave.mrr()).isEqualTo(1D);
        assertThat(empty.emptyScopeCorrectness()).isEqualTo(1D);
        assertThat(missing.recallAtK()).isZero();
        assertThat(scorer.meanMrr(List.of(leave, missing))).isEqualTo(0.5D);

        GoldReplayDump unknown = new GoldReplayDump(GoldReplayDump.SCHEMA_VERSION, "", Map.of("not-a-case", List.of("x")));
        assertThatThrownBy(() -> scorer.score(set, unknown))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown gold case");
    }

    @Test
    @DisplayName("探针 source identity 去重保序后可喂给 scorer")
    void probeIdentitiesFeedScorer() {
        RetrievalProbeResult probe = RetrievalProbeResult.builder()
            .schemaVersion(RetrievalProbeResult.SCHEMA_VERSION)
            .subQuestions(List.of(
                RetrievalProbeSubQuestion.builder()
                    .subQuestionIndex(0)
                    .sourceCandidateIdentities(List.of("CHUNK:101:201", "CHUNK:9:9"))
                    .build(),
                RetrievalProbeSubQuestion.builder()
                    .subQuestionIndex(1)
                    .sourceCandidateIdentities(List.of("CHUNK:101:201", "CHUNK:102:301"))
                    .build()
            ))
            .build();
        assertThat(GoldReplaySupport.sourceIdentities(probe))
            .containsExactly("CHUNK:101:201", "CHUNK:9:9", "CHUNK:102:301");

        ReplayableGoldSetScorer scorer = new ReplayableGoldSetScorer(new ObjectMapper());
        ReplayableGoldSet.GoldCase goldCase = new ReplayableGoldSet.GoldCase(
            "gold-leave-window",
            "请假需要提前多久申请",
            List.of("CHUNK:101:201"),
            ReplayableGoldSet.GoldCase.CASE_TYPE_RANKING,
            5
        );
        ReplayableGoldSetScorer.GoldScore score = scorer.scoreCase(goldCase, GoldReplaySupport.sourceIdentities(probe));
        assertThat(score.recallAtK()).isEqualTo(1D);
        assertThat(score.mrr()).isEqualTo(1D);
        assertThat(score.ndcgAtK()).isCloseTo(1D, within(1e-9));
    }
}
