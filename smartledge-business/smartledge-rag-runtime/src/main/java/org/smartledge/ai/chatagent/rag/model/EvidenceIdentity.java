package org.smartledge.ai.chatagent.rag.model;

public record EvidenceIdentity(String value, CitationEvidenceType type, boolean citationCapable) {

    public static EvidenceIdentity citation(String value, CitationEvidenceType type) {
        return new EvidenceIdentity(value, type, true);
    }

    public static EvidenceIdentity context(String value) {
        return new EvidenceIdentity(value, CitationEvidenceType.CONTEXT_ONLY, false);
    }

    public boolean present() {
        return value != null && !value.isBlank();
    }
}
