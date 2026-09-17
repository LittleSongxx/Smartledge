package org.smartledge.ai.chatagent.rag.model;

import org.smartledge.ai.chatagent.model.SearchReference;

import java.util.Objects;

public record PromptRenderedSourceEvidence(String identity, SearchReference reference, String renderedText) {

    public PromptRenderedSourceEvidence {
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("identity must not be blank");
        }
        reference = Objects.requireNonNull(reference, "reference must not be null");
        renderedText = renderedText == null ? "" : renderedText;
    }

    public PromptRenderedSourceEvidence(String identity, SearchReference reference) {
        this(identity, reference, reference == null || reference.getSnippet() == null ? "" : reference.getSnippet());
    }
}
