package org.smartledge.ai.chatagent.rag.model;

public enum PromptReferenceDisposition {

    PROMPT_RENDERED_SOURCE,
    PROMPT_RENDERED_CONTEXT,
    PROMPT_REUSED_SOURCE,
    OMITTED_BUDGET,
    OMITTED_DUPLICATE,
    OMITTED_INVALID_IDENTITY,
    OMITTED_NOT_APPLICABLE
}
