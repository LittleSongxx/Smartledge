package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.chatagent.model.ConversationExchangeView;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.rag.model.EvidenceAnchor;
import org.smartledge.ai.chatagent.service.ConversationArchiveStore;
import org.smartledge.enums.ChatTurnStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 从上一轮最终引用中抽取追问可继承的结构锚点。
 */
@Service
public class ConversationEvidenceAnchorService {

    private final ConversationArchiveStore conversationArchiveStore;

    public ConversationEvidenceAnchorService(ConversationArchiveStore conversationArchiveStore) {
        this.conversationArchiveStore = conversationArchiveStore;
    }

    public List<EvidenceAnchor> loadRecentEvidenceAnchors(String conversationId, int limit) {
        if (StrUtil.isBlank(conversationId) || limit <= 0 || conversationArchiveStore == null) {
            return List.of();
        }
        List<ConversationExchangeView> exchanges = conversationArchiveStore.listRecentExchanges(conversationId, 3);
        if (exchanges == null || exchanges.isEmpty()) {
            return List.of();
        }
        List<EvidenceAnchor> anchors = new ArrayList<>();
        for (ConversationExchangeView exchange : exchanges) {
            if (exchange == null || !completed(exchange) || exchange.getReferences() == null || exchange.getReferences().isEmpty()) {
                continue;
            }
            for (SearchReference reference : exchange.getReferences()) {
                EvidenceAnchor anchor = fromReference(reference);
                if (anchor == null) {
                    continue;
                }
                anchors.add(anchor);
                if (anchors.size() >= limit) {
                    return anchors;
                }
            }
        }
        return anchors;
    }

    private boolean completed(ConversationExchangeView exchange) {
        return exchange.getStatus() == null || exchange.getStatus() == ChatTurnStatus.COMPLETED;
    }

    private EvidenceAnchor fromReference(SearchReference reference) {
        if (reference == null || !hasUsableAnchor(reference)) {
            return null;
        }
        return EvidenceAnchor.builder()
            .documentId(reference.getDocumentId())
            .documentName(reference.getDocumentName())
            .knowledgeBaseId(reference.getKnowledgeBaseId())
            .knowledgeBaseName(reference.getKnowledgeBaseName())
            .structureNodeId(reference.getStructureNodeId())
            .sectionPath(reference.getSectionPath())
            .canonicalPath(reference.getCanonicalPath())
            .itemIndex(reference.getItemIndex())
            .parentBlockId(reference.getParentBlockId())
            .chunkId(reference.getChunkId())
            .sourceType(reference.getSourceType())
            .channel(reference.getChannel())
            .snippet(clip(reference.getSnippet(), 300))
            .score(reference.getScore())
            .build();
    }

    private boolean hasUsableAnchor(SearchReference reference) {
        return reference.getDocumentId() != null
            || reference.getStructureNodeId() != null
            || reference.getParentBlockId() != null
            || reference.getChunkId() != null
            || StrUtil.isNotBlank(reference.getSectionPath());
    }

    private String clip(String text, int maxChars) {
        String normalized = StrUtil.blankToDefault(text, "").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }
}
