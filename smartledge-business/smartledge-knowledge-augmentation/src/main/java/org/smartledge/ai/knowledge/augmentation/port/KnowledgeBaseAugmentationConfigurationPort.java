package org.smartledge.ai.knowledge.augmentation.port;

import org.smartledge.ai.manage.model.KnowledgeBaseIndexingOptions;

/**
 * Consumer-owned seam for knowledge-base-specific augmentation build options.
 */
public interface KnowledgeBaseAugmentationConfigurationPort {

    KnowledgeBaseIndexingOptions resolveByDocumentId(Long documentId);

    KnowledgeBaseIndexingOptions resolveByKnowledgeBaseId(Long knowledgeBaseId);
}
