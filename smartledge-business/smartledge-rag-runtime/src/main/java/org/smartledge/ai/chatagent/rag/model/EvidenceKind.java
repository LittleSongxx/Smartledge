package org.smartledge.ai.chatagent.rag.model;

/**
 * Prompt/UI 展示用证据种类。有稳定 identity 即可进入 retrieved Source。
 */
public enum EvidenceKind {
    CHUNK,
    PARENT,
    SUMMARY,
    TABLE,
    WEB,
    TOOL;

    public static EvidenceKind from(CitationEvidenceType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case CHUNK, RAPTOR_SOURCE_CHUNK, KG_QUOTE_SOURCE -> CHUNK;
            case TABLE_CELL_OR_ROW -> TABLE;
            case PARENT -> PARENT;
            case SUMMARY -> SUMMARY;
            case WEB -> WEB;
            case TOOL -> TOOL;
            case CONTEXT_ONLY -> null;
        };
    }
}
