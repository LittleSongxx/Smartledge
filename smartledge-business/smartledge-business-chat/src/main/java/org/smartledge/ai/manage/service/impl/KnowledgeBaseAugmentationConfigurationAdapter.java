package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.knowledge.augmentation.port.KnowledgeBaseAugmentationConfigurationPort;
import org.smartledge.ai.manage.model.KnowledgeBaseIndexingOptions;
import org.smartledge.ai.manage.support.KnowledgeBaseIndexingConfigResolver;
import org.springframework.stereotype.Service;

/**
 * Composition-root adapter that keeps the existing knowledge-base resolver out
 * of the augmentation module.
 */
@Service
public class KnowledgeBaseAugmentationConfigurationAdapter implements KnowledgeBaseAugmentationConfigurationPort {

    private final KnowledgeBaseIndexingConfigResolver delegate;

    public KnowledgeBaseAugmentationConfigurationAdapter(KnowledgeBaseIndexingConfigResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public KnowledgeBaseIndexingOptions resolveByDocumentId(Long documentId) {
        return delegate.resolveByDocumentId(documentId);
    }

    @Override
    public KnowledgeBaseIndexingOptions resolveByKnowledgeBaseId(Long knowledgeBaseId) {
        return delegate.resolveByKnowledgeBaseId(knowledgeBaseId);
    }
}
