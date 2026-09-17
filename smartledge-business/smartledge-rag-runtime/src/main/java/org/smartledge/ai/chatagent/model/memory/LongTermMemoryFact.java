package org.smartledge.ai.chatagent.model.memory;

/**
 * Addressable long-term fact. New values supersede the previous ACTIVE row; they never overwrite it.
 */
public record LongTermMemoryFact(
    Long id,
    String conversationId,
    Long userId,
    String entityKey,
    String factText,
    String sourceKind,
    String lifecycle,
    long provenanceExchangeId,
    int version
) {

    public static final String SOURCE_USER_EXPLICIT = "USER_EXPLICIT";
    public static final String SOURCE_MODEL_CANDIDATE = "MODEL_CANDIDATE";
    public static final String LIFECYCLE_ACTIVE = "ACTIVE";
    public static final String LIFECYCLE_SUPERSEDED = "SUPERSEDED";
    public static final String LIFECYCLE_REJECTED = "REJECTED";

    public boolean active() {
        return LIFECYCLE_ACTIVE.equals(lifecycle);
    }
}
