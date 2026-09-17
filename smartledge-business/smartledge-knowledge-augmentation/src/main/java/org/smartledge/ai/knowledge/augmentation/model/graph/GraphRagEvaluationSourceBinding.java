package org.smartledge.ai.knowledge.augmentation.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagEvaluationSourceBinding {

    private String sourceDocument;

    private Long documentId;

    private Long taskId;

    private String documentName;

    private String originalFileName;

    private Boolean matched;

    private String reason;

    public static GraphRagEvaluationSourceBinding missing(String sourceDocument, String reason) {
        return GraphRagEvaluationSourceBinding.builder()
            .sourceDocument(sourceDocument)
            .matched(false)
            .reason(reason)
            .build();
    }
}
