package org.smartledge.ai.rag.runtime.model;

import lombok.Builder;
import lombok.Value;

/** Explicit stage overrides. Null fields retain the configured default at the protocol boundary. */
@Value
@Builder(builderClassName = "Builder")
public class ChatCallOptions {
    String model;
    Integer maxTokens;
    Double temperature;
    Double topP;
    Boolean thinking;
    String reasoningEffort;
    String verbosity;
}
