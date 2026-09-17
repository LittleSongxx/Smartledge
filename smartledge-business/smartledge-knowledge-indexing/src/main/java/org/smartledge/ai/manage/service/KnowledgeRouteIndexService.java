package org.smartledge.ai.manage.service;

import java.util.Collection;
import java.util.List;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface KnowledgeRouteIndexService {

    void refreshIfNeeded();

    RouteLexicalSearchResult search(String routingText, String entityType, int size, Collection<Long> knowledgeBaseIds);

    void deleteDocumentRoute(Long documentId);

    record RouteLexicalHit(
        String routeId,
        Long entityId,
        String entityType,
        Long documentId,
        Long knowledgeBaseId,
        Long scopeId,
        Long topicId,
        String documentName,
        double score
    ) {
    }

    record RouteLexicalSearchResult(List<RouteLexicalHit> hits, boolean available, String reason) {

        public RouteLexicalSearchResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
            reason = reason == null ? "" : reason;
        }

        public static RouteLexicalSearchResult available(List<RouteLexicalHit> hits) {
            return new RouteLexicalSearchResult(hits, true, "");
        }

        public static RouteLexicalSearchResult unavailable(String reason) {
            return new RouteLexicalSearchResult(List.of(), false, reason);
        }
    }
}
