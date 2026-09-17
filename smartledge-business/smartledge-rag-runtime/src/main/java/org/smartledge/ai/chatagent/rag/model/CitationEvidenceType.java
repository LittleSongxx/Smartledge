package org.smartledge.ai.chatagent.rag.model;

public enum CitationEvidenceType {
    CHUNK,
    PARENT,
    SUMMARY,
    TABLE_CELL_OR_ROW,
    KG_QUOTE_SOURCE,
    RAPTOR_SOURCE_CHUNK,
    WEB,
    TOOL,
    CONTEXT_ONLY
}
