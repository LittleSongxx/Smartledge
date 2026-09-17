package org.smartledge.ai.chatagent.evaluation;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.smartledge.ai.chatagent.evaluation.EvaluationSnapshotConfigurationPort;

@Data
@Component
public class EvaluationSnapshotProperties {
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private EvaluationSnapshotConfigurationPort configurationPort;

    @Getter(AccessLevel.NONE)
    private Boolean enabled;

    private Integer maxBatchSize;

    private Integer requestsPerMinute;

    private String promptVersion;

    public boolean isEnabled() { return required(managed().enabled, "evaluation.snapshot.enabled"); }
    public int getMaxBatchSize() { return required(managed().maxBatchSize, "evaluation.snapshot.maxBatchSize"); }
    public int getRequestsPerMinute() { return required(managed().requestsPerMinute, "evaluation.snapshot.requestsPerMinute"); }
    public String getPromptVersion() { return required(managed().promptVersion, "evaluation.snapshot.promptVersion"); }
    private EvaluationSnapshotProperties managed() {
        if (configurationPort == null) return this;
        EvaluationSnapshotProperties configured = configurationPort.current();
        if (configured == null || configured == this) {
            throw new IllegalStateException("Missing required database configuration: evaluation.snapshot");
        }
        return configured;
    }

    private <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalStateException("Missing required database configuration: " + key);
        }
        return value;
    }
}
