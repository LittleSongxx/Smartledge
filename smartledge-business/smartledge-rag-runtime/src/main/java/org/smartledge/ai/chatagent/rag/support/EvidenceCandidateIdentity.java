package org.smartledge.ai.chatagent.rag.support;

import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.CandidateEndpoint;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.IdentityResolutionStatus;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stable physical-candidate identity and capability-independent lineage projection. */
public final class EvidenceCandidateIdentity {

    private EvidenceCandidateIdentity() {
    }

    public static void ensureAll(List<RetrievalDocument> candidates) {
        if (candidates == null) {
            return;
        }
        candidates.stream().filter(java.util.Objects::nonNull).forEach(EvidenceCandidateIdentity::ensure);
    }

    public static void ensure(RetrievalDocument candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate metadata is required");
        }
        Map<String, Object> metadata = candidate.getMetadata();
        String candidateId = text(metadata.get(DocumentKnowledgeMetadataKeys.CANDIDATE_ID));
        if (candidateId.isBlank()) {
            candidateId = "candidate-" + UUID.randomUUID();
            metadata.put(DocumentKnowledgeMetadataKeys.CANDIDATE_ID, candidateId);
        }

        String citationIdentity = EvidenceIdentityResolver.citationIdentityValue(candidate);
        String contextIdentity = EvidenceIdentityResolver.contextIdentityValue(candidate);
        IdentityResolutionStatus status;
        String lineageIdentity;
        if (!citationIdentity.isBlank()) {
            status = IdentityResolutionStatus.CITATION_RESOLVED;
            lineageIdentity = "CITATION:" + citationIdentity;
        }
        else if (isContextCandidate(candidate) && !contextIdentity.isBlank()) {
            status = IdentityResolutionStatus.CONTEXT_RESOLVED;
            lineageIdentity = "CONTEXT:" + contextIdentity;
        }
        else {
            status = IdentityResolutionStatus.CANDIDATE_FALLBACK;
            lineageIdentity = "CANDIDATE:" + candidateId;
        }
        metadata.put(DocumentKnowledgeMetadataKeys.LINEAGE_IDENTITY, lineageIdentity);
        metadata.put(DocumentKnowledgeMetadataKeys.IDENTITY_RESOLUTION_STATUS, status.name());
    }

    public static void assignNew(RetrievalDocument candidate) {
        if (candidate == null || candidate.getMetadata() == null) {
            throw new IllegalArgumentException("candidate metadata is required");
        }
        candidate.getMetadata().remove(DocumentKnowledgeMetadataKeys.CANDIDATE_ID);
        candidate.getMetadata().remove(DocumentKnowledgeMetadataKeys.LINEAGE_IDENTITY);
        candidate.getMetadata().remove(DocumentKnowledgeMetadataKeys.IDENTITY_RESOLUTION_STATUS);
        ensure(candidate);
    }

    public static String candidateId(RetrievalDocument candidate) {
        return requiredMetadataText(candidate, DocumentKnowledgeMetadataKeys.CANDIDATE_ID);
    }

    public static String lineageIdentity(RetrievalDocument candidate) {
        return requiredMetadataText(candidate, DocumentKnowledgeMetadataKeys.LINEAGE_IDENTITY);
    }

    public static IdentityResolutionStatus identityResolutionStatus(RetrievalDocument candidate) {
        String value = requiredMetadataText(candidate, DocumentKnowledgeMetadataKeys.IDENTITY_RESOLUTION_STATUS);
        return IdentityResolutionStatus.valueOf(value);
    }

    public static CandidateEndpoint endpoint(RetrievalDocument candidate) {
        return new CandidateEndpoint(candidateId(candidate), lineageIdentity(candidate), identityResolutionStatus(candidate));
    }

    public static boolean isContextCandidate(RetrievalDocument candidate) {
        if (candidate == null) {
            return false;
        }
        Map<String, Object> metadata = candidate.getMetadata();
        Object contextOnly = metadata.get(DocumentKnowledgeMetadataKeys.CONTEXT_ONLY);
        boolean explicitlyContextOnly = contextOnly instanceof Boolean bool
            ? bool
            : contextOnly != null && Boolean.parseBoolean(String.valueOf(contextOnly));
        return explicitlyContextOnly
            || !text(metadata.get(DocumentKnowledgeMetadataKeys.CONTEXT_ARTIFACT)).isBlank()
            || isDerivedContextProvider(metadata);
    }

    private static boolean isDerivedContextProvider(Map<String, Object> metadata) {
        String sourceType = text(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        return "GRAPH_RAG".equalsIgnoreCase(sourceType)
            || "RAPTOR".equalsIgnoreCase(sourceType);
    }

    private static String requiredMetadataText(RetrievalDocument candidate, String key) {
        if (candidate == null || candidate.getMetadata() == null) {
            throw new IllegalArgumentException("candidate metadata is required");
        }
        String value = text(candidate.getMetadata().get(key));
        if (value.isBlank()) {
            throw new IllegalStateException(key + " is required before the candidate boundary");
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
