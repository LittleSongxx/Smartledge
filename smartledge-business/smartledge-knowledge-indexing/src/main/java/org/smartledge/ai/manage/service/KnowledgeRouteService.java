package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.model.route.KnowledgeRouteContext;
import org.smartledge.ai.manage.model.route.KnowledgeRouteDecision;

/**
 * @description: 服务层
 * @author: Song
 **/
public interface KnowledgeRouteService {

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
