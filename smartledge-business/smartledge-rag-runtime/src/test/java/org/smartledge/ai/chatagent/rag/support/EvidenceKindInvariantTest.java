package org.smartledge.ai.chatagent.rag.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.rag.model.CitationEvidenceType;
import org.smartledge.ai.chatagent.rag.model.EvidenceKind;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceKindInvariantTest {

    @Test
    @DisplayName("RAPTOR 摘要有 node identity 即可引用，种类 SUMMARY")
    void raptorSummaryIsCitationCapable() {
        RetrievalDocument document = document(Map.of(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "RAPTOR",
            DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS, "SUMMARY_ONLY",
            DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, 44L,
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 7L,
            DocumentKnowledgeMetadataKeys.RAPTOR_SUMMARY, "请假需提前三天"
        ));
        assertThat(EvidenceIdentityResolver.isCitationCapable(document)).isTrue();
        assertThat(EvidenceIdentityResolver.citationEvidenceType(document)).isEqualTo(CitationEvidenceType.SUMMARY);
        assertThat(EvidenceIdentityResolver.evidenceKind(document)).isEqualTo(EvidenceKind.SUMMARY);
        assertThat(EvidenceIdentityResolver.citationIdentityValue(document)).isEqualTo("SUMMARY:RAPTOR:44");
    }

    @Test
    @DisplayName("父块有 parentBlockId 即可引用，种类 PARENT")
    void parentBlockIsCitationCapable() {
        RetrievalDocument document = document(Map.of(
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 7L,
            DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 88L,
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "PARENT_BLOCK"
        ));
        assertThat(EvidenceIdentityResolver.isCitationCapable(document)).isTrue();
        assertThat(EvidenceIdentityResolver.evidenceKind(document)).isEqualTo(EvidenceKind.PARENT);
        assertThat(EvidenceIdentityResolver.citationIdentityValue(document)).isEqualTo("PARENT:7:88");
    }

    @Test
    @DisplayName("Graph 社区摘要有 community key 即可引用")
    void graphCommunitySummaryIsCitationCapable() {
        RetrievalDocument document = document(Map.of(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG",
            DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY_ONLY, true,
            DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, "dept-leave",
            DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY, "人事社区综述"
        ));
        assertThat(EvidenceIdentityResolver.isCitationCapable(document)).isTrue();
        assertThat(EvidenceIdentityResolver.evidenceKind(document)).isEqualTo(EvidenceKind.SUMMARY);
        assertThat(EvidenceIdentityResolver.citationIdentityValue(document)).isEqualTo("SUMMARY:KG:dept-leave");
    }

    @Test
    @DisplayName("原文块仍是 CHUNK")
    void rawChunkRemainsChunk() {
        RetrievalDocument document = document(Map.of(
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 7L,
            DocumentKnowledgeMetadataKeys.CHUNK_ID, 9L,
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TEXT"
        ));
        assertThat(EvidenceIdentityResolver.isCitationCapable(document)).isTrue();
        assertThat(EvidenceIdentityResolver.evidenceKind(document)).isEqualTo(EvidenceKind.CHUNK);
    }

    @Test
    @DisplayName("无稳定 identity 的导航壳不可引用")
    void decorativeShellStaysContextOnly() {
        RetrievalDocument document = document(Map.of(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "STRUCTURE_NAVIGATION",
            DocumentKnowledgeMetadataKeys.TITLE, "目录"
        ));
        assertThat(EvidenceIdentityResolver.isCitationCapable(document)).isFalse();
        assertThat(EvidenceIdentityResolver.evidenceKind(document)).isNull();
    }

    private RetrievalDocument document(Map<String, Object> metadata) {
        return RetrievalDocument.builder()
            .id("doc")
            .text("正文")
            .metadata(new HashMap<>(metadata))
            .score(0.5D)
            .build();
    }
}
