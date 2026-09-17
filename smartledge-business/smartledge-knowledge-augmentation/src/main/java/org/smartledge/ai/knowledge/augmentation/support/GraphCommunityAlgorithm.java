package org.smartledge.ai.knowledge.augmentation.support;

import java.util.List;
import java.util.Map;

/**
 * Cross-document community partition. Java still owns persistence and retrieval.
 * Empty membership means the caller must keep the built-in union-find fallback.
 */
public interface GraphCommunityAlgorithm {

    record Edge(String source, String target) {
    }

    Map<String, String> assign(List<String> nodes, List<Edge> edges);
}
