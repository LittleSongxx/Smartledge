package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.manage.service.KnowledgeRouteService;
import org.smartledge.ai.rag.runtime.model.route.DocumentRouteCandidate;
import org.smartledge.ai.rag.runtime.model.route.KnowledgeRouteContext;
import org.smartledge.ai.rag.runtime.model.route.KnowledgeRouteDecision;
import org.smartledge.ai.rag.runtime.model.route.ScopeRouteCandidate;
import org.smartledge.ai.rag.runtime.model.route.TopicRouteCandidate;
import org.smartledge.ai.rag.runtime.port.KnowledgeRoutePort;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;

@Service
public class KnowledgeRoutePortAdapter implements KnowledgeRoutePort {

    private final KnowledgeRouteService delegate;

    public KnowledgeRoutePortAdapter(KnowledgeRouteService delegate) {
        this.delegate = delegate;
    }

    @Override
    public KnowledgeRouteDecision route(KnowledgeRouteContext context) {
        return fromManage(delegate.route(toManage(context)));
    }

    @Override
    public void recordShadowRoute(String conversationId, long exchangeId, Long selectedDocumentId, KnowledgeRouteContext context) {
        delegate.recordShadowRoute(conversationId, exchangeId, selectedDocumentId, toManage(context));
    }

    @Override
    public void recordAutoRoute(String conversationId, long exchangeId, KnowledgeRouteContext context,
                                KnowledgeRouteDecision decision, Long hardScopedDocumentId) {
        delegate.recordAutoRoute(conversationId, exchangeId, toManage(context), toManage(decision), hardScopedDocumentId);
    }

    private static org.smartledge.ai.manage.model.route.KnowledgeRouteContext toManage(KnowledgeRouteContext source) {
        if (source == null) return null;
        return org.smartledge.ai.manage.model.route.KnowledgeRouteContext.builder()
            .question(source.getQuestion())
            .rewriteQuestion(source.getRewriteQuestion())
            .knowledgeBaseSelectionMode(source.getKnowledgeBaseSelectionMode())
            .selectedKnowledgeBaseIds(copyList(source.getSelectedKnowledgeBaseIds()))
            .selectedKnowledgeBaseNames(copyList(source.getSelectedKnowledgeBaseNames()))
            .allowedDocumentIds(copyList(source.getAllowedDocumentIds()))
            .allowedDocuments(source.getAllowedDocuments() == null ? List.of() : source.getAllowedDocuments().stream()
                .map(item -> new org.smartledge.ai.manage.model.KnowledgeDocumentDescriptor(
                    item.getDocumentId(), item.getDocumentName(), item.getLastIndexTaskId(),
                    item.getKnowledgeBaseId(), item.getKnowledgeBaseName()))
                .toList())
            .build();
    }

    private static org.smartledge.ai.manage.model.route.KnowledgeRouteDecision toManage(KnowledgeRouteDecision source) {
        if (source == null) return null;
        org.smartledge.ai.manage.model.route.KnowledgeRouteDecision target = new org.smartledge.ai.manage.model.route.KnowledgeRouteDecision();
        target.setScopes(source.getScopes() == null ? List.of() : source.getScopes().stream().map(KnowledgeRoutePortAdapter::toManage).toList());
        target.setTopics(source.getTopics() == null ? List.of() : source.getTopics().stream().map(KnowledgeRoutePortAdapter::toManage).toList());
        target.setDocuments(source.getDocuments() == null ? List.of() : source.getDocuments().stream().map(KnowledgeRoutePortAdapter::toManage).toList());
        target.setConfidence(source.getConfidence());
        target.setRouteStatus(source.getRouteStatus());
        target.setReason(source.getReason());
        target.setSource(source.getSource());
        target.setDegraded(source.isDegraded());
        target.setDegradedReasons(copyList(source.getDegradedReasons()));
        return target;
    }

    private static org.smartledge.ai.manage.model.route.ScopeRouteCandidate toManage(ScopeRouteCandidate source) {
        org.smartledge.ai.manage.model.route.ScopeRouteCandidate target = new org.smartledge.ai.manage.model.route.ScopeRouteCandidate();
        target.setScopeId(source.getScopeId());
        target.setScopeName(source.getScopeName());
        target.setScore(source.getScore());
        target.setReason(source.getReason());
        target.setSource(source.getSource());
        target.setFeatures(copyMap(source.getFeatures()));
        return target;
    }

    private static org.smartledge.ai.manage.model.route.TopicRouteCandidate toManage(TopicRouteCandidate source) {
        org.smartledge.ai.manage.model.route.TopicRouteCandidate target = new org.smartledge.ai.manage.model.route.TopicRouteCandidate();
        target.setTopicId(source.getTopicId());
        target.setTopicName(source.getTopicName());
        target.setScopeId(source.getScopeId());
        target.setScore(source.getScore());
        target.setReason(source.getReason());
        target.setSource(source.getSource());
        target.setFeatures(copyMap(source.getFeatures()));
        return target;
    }

    private static org.smartledge.ai.manage.model.route.DocumentRouteCandidate toManage(DocumentRouteCandidate source) {
        org.smartledge.ai.manage.model.route.DocumentRouteCandidate target = new org.smartledge.ai.manage.model.route.DocumentRouteCandidate();
        target.setDocumentId(source.getDocumentId());
        target.setDocumentName(source.getDocumentName());
        target.setLastIndexTaskId(source.getLastIndexTaskId());
        target.setScore(source.getScore());
        target.setReason(source.getReason());
        target.setSource(source.getSource());
        target.setFeatures(copyMap(source.getFeatures()));
        return target;
    }

    private static KnowledgeRouteDecision fromManage(org.smartledge.ai.manage.model.route.KnowledgeRouteDecision source) {
        if (source == null) return null;
        KnowledgeRouteDecision target = new KnowledgeRouteDecision();
        target.setScopes(source.getScopes() == null ? List.of() : source.getScopes().stream().map(KnowledgeRoutePortAdapter::fromManage).toList());
        target.setTopics(source.getTopics() == null ? List.of() : source.getTopics().stream().map(KnowledgeRoutePortAdapter::fromManage).toList());
        target.setDocuments(source.getDocuments() == null ? List.of() : source.getDocuments().stream().map(KnowledgeRoutePortAdapter::fromManage).toList());
        target.setConfidence(source.getConfidence());
        target.setRouteStatus(source.getRouteStatus());
        target.setReason(source.getReason());
        target.setSource(source.getSource());
        target.setDegraded(source.isDegraded());
        target.setDegradedReasons(copyList(source.getDegradedReasons()));
        return target;
    }

    private static ScopeRouteCandidate fromManage(org.smartledge.ai.manage.model.route.ScopeRouteCandidate source) {
        ScopeRouteCandidate target = new ScopeRouteCandidate();
        target.setScopeId(source.getScopeId());
        target.setScopeName(source.getScopeName());
        target.setScore(source.getScore());
        target.setReason(source.getReason());
        target.setSource(source.getSource());
        target.setFeatures(copyMap(source.getFeatures()));
        return target;
    }

    private static TopicRouteCandidate fromManage(org.smartledge.ai.manage.model.route.TopicRouteCandidate source) {
        TopicRouteCandidate target = new TopicRouteCandidate();
        target.setTopicId(source.getTopicId());
        target.setTopicName(source.getTopicName());
        target.setScopeId(source.getScopeId());
        target.setScore(source.getScore());
        target.setReason(source.getReason());
        target.setSource(source.getSource());
        target.setFeatures(copyMap(source.getFeatures()));
        return target;
    }

    private static DocumentRouteCandidate fromManage(org.smartledge.ai.manage.model.route.DocumentRouteCandidate source) {
        DocumentRouteCandidate target = new DocumentRouteCandidate();
        target.setDocumentId(source.getDocumentId());
        target.setDocumentName(source.getDocumentName());
        target.setLastIndexTaskId(source.getLastIndexTaskId());
        target.setScore(source.getScore());
        target.setReason(source.getReason());
        target.setSource(source.getSource());
        target.setFeatures(copyMap(source.getFeatures()));
        return target;
    }

    private static <T> List<T> copyList(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private static <K, V> LinkedHashMap<K, V> copyMap(java.util.Map<K, V> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
