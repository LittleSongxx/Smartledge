package org.smartledge.ai.chatagent.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.rag.model.EvidenceCandidatePools;
import org.smartledge.ai.chatagent.rag.support.EvidenceIdentityResolver;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.smartledge.ai.rag.runtime.port.DocumentEvidencePort;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContextExpansionPlannerEvidenceKindTest {

    @Test
    @DisplayName("摘要与父块进入 Source；无 identity 导航壳留在 Context")
    void summaryAndParentEnterSource() {
        ContextExpansionPlanner planner = new ContextExpansionPlanner(mock(DocumentEvidencePort.class), new ChatRagProperties());
        RetrievalDocument summary = document("raptor-summary", Map.of(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "RAPTOR",
            DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS, "SUMMARY_ONLY",
            DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, 3L,
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L,
            DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, "综述句"
        ));
        RetrievalDocument parent = document("parent", Map.of(
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L,
            DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 22L,
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "PARENT_BLOCK"
        ));
        RetrievalDocument nav = document("nav", Map.of(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "STRUCTURE_NAVIGATION",
            DocumentKnowledgeMetadataKeys.TITLE, "目录"
        ));
        EvidenceCandidatePools pools = planner.partitionCandidates(List.of(summary, parent), List.of(nav));
        assertThat(pools.sourceDocuments()).hasSize(2);
        assertThat(pools.sourceDocuments()).allMatch(EvidenceIdentityResolver::isCitationCapable);
        assertThat(pools.contextDocuments()).hasSize(1);
        assertThat(EvidenceIdentityResolver.isCitationCapable(pools.contextDocuments().get(0))).isFalse();
    }

    private RetrievalDocument document(String id, Map<String, Object> metadata) {
        return RetrievalDocument.builder()
            .id(id)
            .text("text")
            .metadata(new HashMap<>(metadata))
            .score(0.4D)
            .build();
    }
}
