package org.smartledge.ai.chatagent.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationContractReadinessTest {

    @Test
    @DisplayName("完整手续清单五族都 ready，且不再使用质量指标旧名")
    void completeContractIsReadyWithoutQualityAliases() {
        List<EvaluationExchangeSnapshot.Readiness> readiness = EvaluationContractReadiness.evaluate(complete());
        assertThat(readiness).extracting(EvaluationExchangeSnapshot.Readiness::family)
            .containsExactly(
                EvaluationContractReadiness.DETERMINISTIC_CONTRACT,
                EvaluationContractReadiness.RETRIEVAL_TRACE_READY,
                EvaluationContractReadiness.ANSWER_PRESENT,
                EvaluationContractReadiness.CONTRACT_READY,
                EvaluationContractReadiness.CITATION_BINDING_READY
            );
        assertThat(readiness).allMatch(EvaluationExchangeSnapshot.Readiness::ready);
        assertThat(readiness).extracting(EvaluationExchangeSnapshot.Readiness::family)
            .doesNotContain("FAITHFULNESS", "RETRIEVAL_RELEVANCE", "ANSWER_RELEVANCE", "CITATION_SUPPORT");
    }

    @Test
    @DisplayName("无 manifest / rendered Source 只打 CONTRACT_READY，不叫 faithfulness")
    void missingManifestIsContractNotFaithfulness() {
        EvaluationContractReadiness.Request request = new EvaluationContractReadiness.Request(
            List.of(), true, true, "CONSERVED", List.of(),
            true, true, true, true, true,
            true, true, false,
            "COMPLETED", true, true,
            false, false, true, true
        );
        EvaluationExchangeSnapshot.Readiness contract = family(EvaluationContractReadiness.evaluate(request),
            EvaluationContractReadiness.CONTRACT_READY);
        assertThat(contract.ready()).isFalse();
        assertThat(contract.reasons()).containsExactly("MISSING_PROMPT_MANIFEST", "NO_RENDERED_SOURCE_EVIDENCE");
        assertThat(family(EvaluationContractReadiness.evaluate(request), EvaluationContractReadiness.ANSWER_PRESENT).ready())
            .isTrue();
    }

    @Test
    @DisplayName("Plan/channel 缺失是检索痕迹未齐，不是 relevance")
    void missingPlanIsTraceNotRelevance() {
        EvaluationContractReadiness.Request request = new EvaluationContractReadiness.Request(
            List.of(), true, true, "CONSERVED", List.of(),
            true, true, true, true, true,
            false, false, true,
            "COMPLETED", true, true,
            true, true, true, true
        );
        EvaluationExchangeSnapshot.Readiness retrieval = family(EvaluationContractReadiness.evaluate(request),
            EvaluationContractReadiness.RETRIEVAL_TRACE_READY);
        assertThat(retrieval.ready()).isFalse();
        assertThat(retrieval.reasons()).containsExactly(
            "MISSING_RETRIEVAL_PLAN",
            "MISSING_CHANNEL_EXECUTION",
            "MISSING_GRAPH_RAG_HINT_CONSUMPTION_FACT"
        );
    }

    @Test
    @DisplayName("守恒破坏只影响 citation binding 手续，不表示句子被证据支持")
    void conservationViolationIsBindingContract() {
        EvaluationContractReadiness.Request request = new EvaluationContractReadiness.Request(
            List.of(), true, true, "VIOLATED", List.of("RETRIEVED_SOURCE_PROMPT_MISMATCH"),
            true, true, true, true, true,
            true, true, false,
            "COMPLETED", true, true,
            true, true, true, true
        );
        EvaluationExchangeSnapshot.Readiness citation = family(EvaluationContractReadiness.evaluate(request),
            EvaluationContractReadiness.CITATION_BINDING_READY);
        assertThat(citation.ready()).isFalse();
        assertThat(citation.reasons()).contains("IDENTITY_CONSERVATION_VIOLATED");
        EvaluationExchangeSnapshot.Readiness deterministic = family(EvaluationContractReadiness.evaluate(request),
            EvaluationContractReadiness.DETERMINISTIC_CONTRACT);
        assertThat(deterministic.ready()).isFalse();
        assertThat(deterministic.reasons()).contains("RETRIEVED_SOURCE_PROMPT_MISMATCH");
    }

    private static EvaluationContractReadiness.Request complete() {
        return new EvaluationContractReadiness.Request(
            List.of(), true, true, "CONSERVED", List.of(),
            true, true, true, true, true,
            true, true, false,
            "COMPLETED", true, true,
            true, true, true, true
        );
    }

    private static EvaluationExchangeSnapshot.Readiness family(List<EvaluationExchangeSnapshot.Readiness> readiness,
                                                              String family) {
        return readiness.stream()
            .filter(item -> family.equals(item.family()))
            .findFirst()
            .orElseThrow();
    }
}
