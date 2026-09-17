package org.smartledge.ai.chatagent.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.rag.model.EvidenceApplicabilityPlan;
import org.smartledge.ai.chatagent.rag.model.FinalEvidenceDecision;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinalEvidenceSelectionPolicyInvariantTest {

    private final FinalEvidenceSelectionPolicy policy = new FinalEvidenceSelectionPolicy(new EvidenceApplicabilityService());

    @Test
    @DisplayName("Context Only 候选不得进入最终 Source")
    void filtersContextOnly() {
        RetrievalDocument context = RetrievalDocument.builder()
            .id("ctx")
            .text("摘要")
            .metadata(new HashMap<>(Map.of(
                DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, 9L,
                DocumentKnowledgeMetadataKeys.RERANK_STATUS, FinalEvidenceDecision.RerankStatus.NOT_REQUESTED.name(),
                DocumentKnowledgeMetadataKeys.SCORE, 0.99D
            )))
            .score(0.99D)
            .build();
        var result = policy.select(List.of(context), plan(2, null));
        assertThat(result.selectedDocuments()).isEmpty();
        assertThat(result.decisions()).allMatch(decision -> decision.disposition() == FinalEvidenceDecision.Disposition.FILTERED);
    }

    @Test
    @DisplayName("同一 citation identity 只选一条，并遵守 budget")
    void deduplicatesAndHonorsBudget() {
        RetrievalDocument first = chunk(1L, 11L, 0.8D, FinalEvidenceDecision.RerankStatus.NOT_REQUESTED, null);
        RetrievalDocument duplicate = chunk(1L, 11L, 0.7D, FinalEvidenceDecision.RerankStatus.NOT_REQUESTED, null);
        RetrievalDocument second = chunk(2L, 22L, 0.6D, FinalEvidenceDecision.RerankStatus.NOT_REQUESTED, null);
        var result = policy.select(List.of(first, duplicate, second), plan(1, null));
        assertThat(result.selectedDocuments()).hasSize(1);
        assertThat(result.decisions().stream().filter(decision -> decision.disposition() == FinalEvidenceDecision.Disposition.SELECTED))
            .hasSize(1);
        assertThat(result.decisions().stream().filter(decision ->
            decision.reason() == FinalEvidenceDecision.Reason.FILTERED_DUPLICATE_SOURCE_IDENTITY
                || decision.reason() == FinalEvidenceDecision.Reason.FILTERED_BY_FINAL_EVIDENCE_BUDGET
        )).hasSize(2);
    }

    @Test
    @DisplayName("rerank 成功后第一名进入 top-k，不被实体 contains 否决")
    void rerankFirstEntersTopKDespiteExcludedEntityMention() {
        RetrievalDocument rerankFirst = chunk(3L, 31L, 0.1D, FinalEvidenceDecision.RerankStatus.SUCCESS, 0.99D);
        rerankFirst.getMetadata().put(DocumentKnowledgeMetadataKeys.TITLE, "只提到被排除实体 财务部");
        rerankFirst.getText();
        RetrievalDocument rerankSecond = chunk(4L, 41L, 0.9D, FinalEvidenceDecision.RerankStatus.SUCCESS, 0.2D);
        rerankSecond.getMetadata().put(DocumentKnowledgeMetadataKeys.TITLE, "目标实体 产品概述");
        EvidenceApplicabilityPlan applicability = EvidenceApplicabilityPlan.authorizedExclusion(
            List.of("产品概述"),
            List.of("财务部"),
            "test",
            "authorized"
        );
        var result = policy.select(List.of(rerankFirst, rerankSecond), plan(1, applicability));
        assertThat(result.selectedDocuments()).hasSize(1);
        assertThat(result.selectedDocuments().get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_ID))
            .isEqualTo(31L);
        assertThat(result.decisions().stream().noneMatch(decision ->
            decision.reason() == FinalEvidenceDecision.Reason.FILTERED_NOT_APPLICABLE)).isTrue();
    }

    @Test
    @DisplayName("近分差下 KG/质量加分不得挤掉 citation-capable 输入序第一名")
    void qualityBoostCannotDisplaceInputOrderPrefix() {
        RetrievalDocument first = chunk(5L, 51L, 0.51D, FinalEvidenceDecision.RerankStatus.SUCCESS, 0.51D);
        RetrievalDocument second = chunk(6L, 61L, 0.49D, FinalEvidenceDecision.RerankStatus.SUCCESS, 0.49D);
        second.getMetadata().put(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 1.0D);
        second.getMetadata().put(DocumentKnowledgeMetadataKeys.KG_RANK_BOOST, 1.0D);
        var result = policy.select(List.of(first, second), plan(1, null));
        assertThat(result.selectedDocuments()).hasSize(1);
        assertThat(result.selectedDocuments().get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_ID))
            .isEqualTo(51L);
    }

    private RetrievalPlan plan(int budget, EvidenceApplicabilityPlan applicability) {
        return RetrievalPlan.builder()
            .finalEvidenceBudget(budget)
            .evidenceApplicabilityPlan(applicability)
            .build();
    }

    private RetrievalDocument chunk(long documentId,
                                    long chunkId,
                                    double score,
                                    FinalEvidenceDecision.RerankStatus rerankStatus,
                                    Double rerankScore) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, documentId);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, chunkId);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TEXT");
        metadata.put(DocumentKnowledgeMetadataKeys.RERANK_STATUS, rerankStatus.name());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        if (rerankScore != null) {
            metadata.put(DocumentKnowledgeMetadataKeys.RERANK_SCORE, rerankScore);
        }
        return RetrievalDocument.builder()
            .id(documentId + ":" + chunkId)
            .text("正文")
            .metadata(metadata)
            .score(score)
            .build();
    }
}
