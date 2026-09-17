package org.smartledge.ai.knowledge.augmentation.support;

import cn.hutool.core.collection.CollUtil;
import org.smartledge.ai.manage.data.SuperAgentDocument;

import java.util.LinkedHashSet;
import java.util.List;

public final class RaptorScopeSupport {

    public static final String SCOPE_TYPE_DOCUMENT = "DOCUMENT";

    public static final String SCOPE_TYPE_DATASET = "DATASET";

    public static final String GLOBAL_SCOPE_KEY = "global";

    private RaptorScopeSupport() {
    }

    public static String documentScopeKey(Long documentId) {
        return "document:" + documentId;
    }

    public static String knowledgeBaseScopeKey(Long knowledgeBaseId) {
        return "kb:" + knowledgeBaseId;
    }

    public static String knowledgeScopeKey(Long knowledgeBaseId, Long scopeId) {
        return knowledgeBaseScopeKey(knowledgeBaseId) + ":scope:" + scopeId;
    }

    public static List<String> searchScopeKeys(List<SuperAgentDocument> documents) {
        if (CollUtil.isEmpty(documents)) {
            return List.of();
        }
        LinkedHashSet<String> scopeKeys = new LinkedHashSet<>();
        for (SuperAgentDocument document : documents) {
            if (document == null || document.getKnowledgeBaseId() == null) {
                continue;
            }
            scopeKeys.add(knowledgeBaseScopeKey(document.getKnowledgeBaseId()));
        }
        return List.copyOf(scopeKeys);
    }

    public static boolean isDatasetScope(String scopeType) {
        return SCOPE_TYPE_DATASET.equalsIgnoreCase(scopeType == null ? "" : scopeType);
    }

}
