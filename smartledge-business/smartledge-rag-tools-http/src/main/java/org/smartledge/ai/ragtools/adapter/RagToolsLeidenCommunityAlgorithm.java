package org.smartledge.ai.ragtools.adapter;

import org.smartledge.ai.knowledge.augmentation.support.GraphCommunityAlgorithm;
import org.smartledge.ai.ragtools.client.RagToolsClient;
import org.smartledge.ai.ragtools.model.RagToolsGraphCommunityRequest;
import org.smartledge.ai.ragtools.model.RagToolsGraphCommunityResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Python Leiden (or Louvain fallback). Empty map lets Java keep union-find. */
@Component
public final class RagToolsLeidenCommunityAlgorithm implements GraphCommunityAlgorithm {

    private final RagToolsClient client;

    public RagToolsLeidenCommunityAlgorithm(RagToolsClient client) {
        this.client = client;
    }

    @Override
    public Map<String, String> assign(List<String> nodes, List<Edge> edges) {
        RagToolsGraphCommunityRequest request = new RagToolsGraphCommunityRequest();
        request.setNodes(nodes == null ? List.of() : List.copyOf(nodes));
        request.setEdges(edges == null ? List.of() : edges.stream()
            .map(edge -> new RagToolsGraphCommunityRequest.Edge(edge.source(), edge.target()))
            .toList());
        try {
            RagToolsGraphCommunityResponse response = client.detectCommunities(request);
            if (response == null || response.getMembership() == null || response.getMembership().isEmpty()) {
                return Map.of();
            }
            return Map.copyOf(response.getMembership());
        }
        catch (RuntimeException ignored) {
            return Map.of();
        }
    }
}
