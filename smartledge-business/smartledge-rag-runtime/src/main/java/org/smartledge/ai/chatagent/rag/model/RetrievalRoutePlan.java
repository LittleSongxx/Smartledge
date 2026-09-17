package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Advisory route candidate pool plus the explicit business-scope authorization projection. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalRoutePlan {

    private String source;

    private String status;

    private double confidence;

    private boolean degraded;

    @Builder.Default
    private List<String> degradedReasons = new ArrayList<>();

    private Long topDocumentHintId;

    private Long topTaskHintId;

    private RouteScopeAuthorizationMode authorizationMode;

    private String scopeAuthorizationReason;

    @Builder.Default
    private List<Long> authorizedDocumentIds = new ArrayList<>();

    @Builder.Default
    private List<Long> authorizedTaskIds = new ArrayList<>();

    @Builder.Default
    private List<RetrievalRouteCandidate> candidates = new ArrayList<>();
}
