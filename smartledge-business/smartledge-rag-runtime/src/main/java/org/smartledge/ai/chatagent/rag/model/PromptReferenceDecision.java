package org.smartledge.ai.chatagent.rag.model;

import java.util.Objects;

public record PromptReferenceDecision(
    int inputOrdinal,
    int subQuestionIndex,
    String referenceId,
    String renderedReferenceId,
    String sourceType,
    String identity,
    PromptReferenceDisposition disposition,
    int consumedChars
) {

    public PromptReferenceDecision {
        if (inputOrdinal < 0) {
            throw new IllegalArgumentException("inputOrdinal must not be negative");
        }
        if (consumedChars < 0) {
            throw new IllegalArgumentException("consumedChars must not be negative");
        }
        referenceId = normalize(referenceId);
        renderedReferenceId = normalize(renderedReferenceId);
        sourceType = normalize(sourceType);
        identity = normalize(identity);
        disposition = Objects.requireNonNull(disposition, "disposition must not be null");
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
