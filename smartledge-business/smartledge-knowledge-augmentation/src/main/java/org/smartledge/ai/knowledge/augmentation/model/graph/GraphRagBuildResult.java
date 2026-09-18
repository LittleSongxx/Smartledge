package org.smartledge.ai.knowledge.augmentation.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagBuildResult {

    private Integer entityCount;

    private Integer relationCount;

    private Integer evidenceCount;

    private GraphPersistenceOutcome graphPersistenceOutcome;

    private String graphPersistenceReason;

    private Boolean kgCommitted;

    private ComponentOutcome typedIndexOutcome;

    private ComponentOutcome crossDocumentIndexOutcome;

    private DerivedIndexOutcome derivedIndexOutcome;

    private ObservationProjectionOutcome observationProjectionOutcome;

    private OuterTaskDisposition outerTaskDisposition;

    private InvocationOutcome pythonInvocationOutcome;

    private InvocationOutcome advisorInvocationOutcome;

    private String pythonExtractionStatus;

    private String advisorReason;

    @Builder.Default
    private List<String> degradationReasons = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> extractionMetadata = new LinkedHashMap<>();

    private Integer attempt;

    private Integer maxAttempts;

    public enum GraphPersistenceOutcome {
        SUCCESS,
        DEGRADED,
        EMPTY,
        FAILED
    }

    public enum ComponentOutcome {
        NOT_APPLICABLE,
        SUCCESS,
        FAILED
    }

    public enum DerivedIndexOutcome {
        NOT_APPLICABLE,
        SUCCESS,
        FAILED
    }

    public enum ObservationProjectionOutcome {
        SUCCESS,
        PARTIAL,
        FAILED
    }

    public enum OuterTaskDisposition {
        CONTINUE,
        FAIL_INDEX_TASK,
        REPAIR_REQUIRED
    }

    public enum InvocationOutcome {
        NOT_CALLED,
        SUCCESS,
        EMPTY,
        NOT_GRAPHABLE,
        DISABLED,
        TRANSPORT_FAILED,
        INVALID_RESPONSE,
        FAILED
    }
}
