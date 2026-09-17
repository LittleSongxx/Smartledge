package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceApplicabilityResult {

    public static final String APPLICABLE = "APPLICABLE";
    public static final String APPLICABLE_UNKNOWN = "APPLICABLE_UNKNOWN";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    private String status;

    private boolean applicable;

    private String reason;

    public static EvidenceApplicabilityResult applicable(String reason) {
        return EvidenceApplicabilityResult.builder()
            .status(APPLICABLE)
            .applicable(true)
            .reason(reason)
            .build();
    }

    public static EvidenceApplicabilityResult unknown(String reason) {
        return EvidenceApplicabilityResult.builder()
            .status(APPLICABLE_UNKNOWN)
            .applicable(true)
            .reason(reason)
            .build();
    }

    public static EvidenceApplicabilityResult notApplicable(String reason) {
        return EvidenceApplicabilityResult.builder()
            .status(NOT_APPLICABLE)
            .applicable(false)
            .reason(reason)
            .build();
    }
}
