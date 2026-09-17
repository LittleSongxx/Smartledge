package org.smartledge.ai.rag.runtime.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Retrieval candidate payload. Identity and Source/Context eligibility belong to the evidence policies. */
public final class RetrievalDocument {
    private final String id;
    private final String text;
    private final Map<String, Object> metadata;
    private final Double score;

    private RetrievalDocument(Builder builder) {
        this.id = builder.id;
        this.text = Objects.requireNonNull(builder.text, "text");
        this.metadata = new LinkedHashMap<>(builder.metadata);
        this.score = builder.score;
    }

    public String getId() { return id; }
    public String getText() { return text; }
    /** Mutable per-candidate metadata; construction makes a shallow copy, preserving value types. */
    public Map<String, Object> getMetadata() { return metadata; }
    public Double getScore() { return score; }
    @Override public boolean equals(Object other) {
        return other instanceof RetrievalDocument document && Objects.equals(id, document.id)
            && Objects.equals(text, document.text) && Objects.equals(metadata, document.metadata)
            && Objects.equals(score, document.score);
    }
    @Override public int hashCode() { return Objects.hash(id, text, metadata, score); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id;
        private String text;
        private Map<String, Object> metadata = Map.of();
        private Double score;
        public Builder id(String value) { id = value; return this; }
        public Builder text(String value) { text = value; return this; }
        public Builder metadata(Map<String, Object> value) { metadata = Objects.requireNonNull(value); return this; }
        public Builder score(Double value) { score = value; return this; }
        public RetrievalDocument build() { return new RetrievalDocument(this); }
    }
}
