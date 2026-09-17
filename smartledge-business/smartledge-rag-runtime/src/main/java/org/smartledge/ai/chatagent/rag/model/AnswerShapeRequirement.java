package org.smartledge.ai.chatagent.rag.model;

/** Controlled answer-organization requirements produced by query understanding. */
public enum AnswerShapeRequirement {
    COMPARE,
    LIST,
    STEPS,
    NEGATIVE_BOUNDARY,
    CATEGORY_TABLE_ROW_COVERAGE
}
