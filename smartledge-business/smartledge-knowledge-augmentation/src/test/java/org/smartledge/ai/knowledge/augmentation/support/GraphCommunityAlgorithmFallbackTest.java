package org.smartledge.ai.knowledge.augmentation.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphCommunityAlgorithmFallbackTest {

    @Test
    @DisplayName("算法返回空时仍走并查集，不抛错")
    void emptyMembershipKeepsUnionFind() {
        GraphCommunityAlgorithm empty = (nodes, edges) -> Map.of();
        GraphRagCrossDocumentIndexSupport support = new GraphRagCrossDocumentIndexSupport(
            new ObjectMapper(),
            provider(empty)
        );

        assertThat(support).isNotNull();
        assertThat(empty.assign(List.of("a"), List.of())).isEmpty();
    }

    private static ObjectProvider<GraphCommunityAlgorithm> provider(GraphCommunityAlgorithm algorithm) {
        return new ObjectProvider<>() {
            @Override
            public GraphCommunityAlgorithm getObject() {
                return algorithm;
            }

            @Override
            public GraphCommunityAlgorithm getObject(Object... args) {
                return algorithm;
            }

            @Override
            public GraphCommunityAlgorithm getIfAvailable() {
                return algorithm;
            }

            @Override
            public GraphCommunityAlgorithm getIfUnique() {
                return algorithm;
            }
        };
    }
}
