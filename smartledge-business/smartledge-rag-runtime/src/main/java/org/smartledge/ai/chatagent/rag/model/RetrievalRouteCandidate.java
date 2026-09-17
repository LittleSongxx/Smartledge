package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Route candidate copied into an owned, serializable RetrievalPlan model. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalRouteCandidate {

    private String candidateType;

    private Long candidateId;

    private Long documentId;

    private Long taskId;

    private Long scopeId;

    private Long topicId;

    private String displayName;

    private double score;

    private String reason;

    private String source;

    @Builder.Default
    private Map<String, Double> features = new LinkedHashMap<>();
}
