package org.smartledge.ai.chatagent.evaluation.probe;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Data
@Component
public class RetrievalProbeProperties {
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private RetrievalProbeConfigurationPort configurationPort;

    @Getter(AccessLevel.NONE)
    private Boolean enabled;

    private Integer requestsPerMinute;

    private Integer maxQueryLength;

    private Integer maxResultCount;

    public boolean isEnabled() { return required(managed().enabled, "evaluation.retrievalProbe.enabled"); }
    public int getRequestsPerMinute() { return required(managed().requestsPerMinute, "evaluation.retrievalProbe.requestsPerMinute"); }
    public int getMaxQueryLength() { return required(managed().maxQueryLength, "evaluation.retrievalProbe.maxQueryLength"); }
    public int getMaxResultCount() { return required(managed().maxResultCount, "evaluation.retrievalProbe.maxResultCount"); }
    private RetrievalProbeProperties managed() {
        if (configurationPort == null) return this;
        RetrievalProbeProperties configured = configurationPort.current();
        if (configured == null || configured == this) {
            throw new IllegalStateException("Missing required database configuration: evaluation.retrievalProbe");
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
