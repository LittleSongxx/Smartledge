package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Generic ranking features and weights accepted by the retrieval contract. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankFeatureBundle {

    @Builder.Default
    private List<String> enabledFeatures = new ArrayList<>();

    private double rankWeight;

    private double originalScoreWeight;

    private double metadataBoostWeight;

    private double maxMetadataBoost;

    @Builder.Default
    private String source = "PERSISTED_INDEX_METADATA";
}
