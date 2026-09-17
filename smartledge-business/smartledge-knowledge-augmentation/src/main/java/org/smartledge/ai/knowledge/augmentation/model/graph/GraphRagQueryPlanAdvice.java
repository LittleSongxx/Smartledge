package org.smartledge.ai.knowledge.augmentation.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagQueryPlanAdvice {

    private Boolean graphQuery;

    private String queryIntent;

    @Builder.Default
    private List<String> focusEntities = new ArrayList<>();

    @Builder.Default
    private List<String> genericIntentTerms = new ArrayList<>();

    @Builder.Default
    private List<String> entityNames = new ArrayList<>();

    @Builder.Default
    private List<String> entityTypes = new ArrayList<>();

    @Builder.Default
    private List<String> relationTypes = new ArrayList<>();

    @Builder.Default
    private List<String> answerTypeKeywords = new ArrayList<>();

    @Builder.Default
    private List<String> entitiesFromQuery = new ArrayList<>();

    @Builder.Default
    private List<Long> entityIds = new ArrayList<>();

    @Builder.Default
    private List<Long> relationIds = new ArrayList<>();

    @Builder.Default
    private List<Long> communityIds = new ArrayList<>();

    private Boolean relationQuestion;

    private Boolean communityQuestion;

    private Integer maxHops;

    private Double confidence;

    private String reason;
}
