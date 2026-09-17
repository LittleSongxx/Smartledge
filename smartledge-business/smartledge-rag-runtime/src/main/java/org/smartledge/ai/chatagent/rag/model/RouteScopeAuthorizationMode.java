package org.smartledge.ai.chatagent.rag.model;

/** The explicit business boundary that authorizes the executable document scope. */
public enum RouteScopeAuthorizationMode {
    EXPLICIT_DOCUMENT,
    KNOWLEDGE_BASE_ALLOWED_SCOPE,
    CLARIFICATION_REQUIRED
}
