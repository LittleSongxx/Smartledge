package org.smartledge.ai.chatagent.rag.support;

import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 候选归一化：把各通道 {@link RetrievalDocument} 归一为统一候选视图——解析 citation/context 身份、写回身份 metadata、
 * 提供跨阶段稳定候选 id 与候选身份比较。逻辑委托给 {@link EvidenceIdentityResolver}，本类只做归一与比较，
 * 不改任何分数、reason 字符串或控制流。检索引擎、证据预算分配、上下文扩展都复用同一套候选身份判断。
 */
public final class EvidenceCandidateNormalizer {

    private EvidenceCandidateNormalizer() {
    }

    /**
     * 把候选的 citation/context 身份、可引用类型、context-only 标记写回 metadata，供观测与后续预算/引用边界统一读取。
     */
    public static void enrichIdentity(RetrievalDocument document) {
        if (document == null || document.getMetadata() == null) {
            return;
        }
        Map<String, Object> metadata = document.getMetadata();
        String citationIdentity = EvidenceIdentityResolver.citationIdentityValue(document);
        String contextIdentity = EvidenceIdentityResolver.contextIdentityValue(document);
        if (!citationIdentity.isBlank()) {
            metadata.put(DocumentKnowledgeMetadataKeys.CITATION_IDENTITY, citationIdentity);
        }
        else {
            metadata.remove(DocumentKnowledgeMetadataKeys.CITATION_IDENTITY);
        }
        if (!contextIdentity.isBlank()) {
            metadata.put(DocumentKnowledgeMetadataKeys.CONTEXT_IDENTITY, contextIdentity);
        }
        else {
            metadata.remove(DocumentKnowledgeMetadataKeys.CONTEXT_IDENTITY);
        }
        metadata.put(DocumentKnowledgeMetadataKeys.CITATION_EVIDENCE_TYPE, EvidenceIdentityResolver.citationEvidenceType(document).name());
        boolean citationCapable = EvidenceIdentityResolver.isCitationCapable(document);
        boolean contextOnly = !citationCapable && EvidenceCandidateIdentity.isContextCandidate(document);
        metadata.put(DocumentKnowledgeMetadataKeys.CONTEXT_ONLY, contextOnly);
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_EVIDENCE_RESOLVED, citationCapable);
        EvidenceQualityFeatures.resolve(document).writeTo(metadata);
        EvidenceCandidateIdentity.ensure(document);
    }

    /**
     * O9 physical candidate identity. Capability and lineage identities stay separate.
     */
    public static String resolveCandidateId(RetrievalDocument document) {
        if (document == null) {
            return "";
        }
        EvidenceCandidateIdentity.ensure(document);
        return EvidenceCandidateIdentity.candidateId(document);
    }

    /**
     * 广义候选身份相等：同一 RetrievalDocument id、或同一 citation 身份、或两者都是 context-only 且同一 context 身份。
     * 用于跨阶段（合并/召回/rerank/观测）识别同一候选。
     */
    public static boolean sameEvidenceIdentity(RetrievalDocument left, RetrievalDocument right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getId() != null && !left.getId().isBlank() && Objects.equals(left.getId(), right.getId())) {
            return true;
        }
        if (EvidenceIdentityResolver.sameCitationEvidence(left, right)) {
            return true;
        }
        return EvidenceIdentityResolver.isContextOnly(left)
            && EvidenceIdentityResolver.isContextOnly(right)
            && EvidenceIdentityResolver.sameContext(left, right);
    }

    /**
     * 可引用证据身份相等：同一 RetrievalDocument id、或同一 citation 身份。用于证据预算去重（只按可引用身份判定）。
     */
    public static boolean sameCitationEvidence(RetrievalDocument left, RetrievalDocument right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getId() != null && Objects.equals(left.getId(), right.getId())) {
            return true;
        }
        return EvidenceIdentityResolver.sameCitationEvidence(left, right);
    }

    public static boolean containsSameCitationEvidence(List<RetrievalDocument> documents, RetrievalDocument candidate) {
        return documents != null
            && documents.stream().anyMatch(document -> sameCitationEvidence(document, candidate));
    }

    public static RetrievalDocument findSameCitationEvidence(List<RetrievalDocument> documents, RetrievalDocument candidate) {
        if (documents == null || candidate == null) {
            return null;
        }
        return documents.stream()
            .filter(document -> sameCitationEvidence(document, candidate))
            .findFirst()
            .orElse(null);
    }
}
