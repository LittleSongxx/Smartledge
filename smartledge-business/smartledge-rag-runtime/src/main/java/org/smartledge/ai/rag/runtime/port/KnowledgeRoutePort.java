package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.route.KnowledgeRouteContext;
import org.smartledge.ai.rag.runtime.model.route.KnowledgeRouteDecision;

public interface KnowledgeRoutePort {

    KnowledgeRouteDecision route(KnowledgeRouteContext context);

    void recordShadowRoute(String conversationId,
                           long exchangeId,
                           Long selectedDocumentId,
                           KnowledgeRouteContext context);

    void recordAutoRoute(String conversationId,
                         long exchangeId,
                         KnowledgeRouteContext context,
                         KnowledgeRouteDecision decision,
                         Long hardScopedDocumentId);
}
