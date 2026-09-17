package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRagArtifactGraphWindowVo {

    private Long documentId;

    private Long parseTaskId;

    private Long indexTaskId;

    private Stats stats;

    private List<NodeItem> nodes;

    private List<EdgeItem> edges;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stats {

        private Long totalEntities;

        private Long connectedEntities;

        private Long isolatedEntities;

        private Long totalRelations;

        private Integer returnedNodes;

        private Integer returnedEdges;

        private boolean truncated;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeItem {

        private String nodeId;

        private Long sourceId;

        private String label;

        private String entityType;

        private Long incomingCount;

        private Long outgoingCount;

        public long getDegree() {
            return safeCount(incomingCount) + safeCount(outgoingCount);
        }

        private long safeCount(Long value) {
            return value == null ? 0L : value;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EdgeItem {

        private String edgeId;

        private Long relationId;

        private String sourceNodeId;

        private String targetNodeId;

        private String relationType;

        private String description;

        private BigDecimal weight;
    }
}
