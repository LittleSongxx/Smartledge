package org.smartledge.ai.knowledge.augmentation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Augmentation-owned projection used by the bounded GraphRAG artifact window.
 * Management HTTP response types stay in the composition root.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphWindowNodeProjection {

    private String nodeId;

    private Long sourceId;

    private String label;

    private String entityType;

    private Long incomingCount;

    private Long outgoingCount;
}
