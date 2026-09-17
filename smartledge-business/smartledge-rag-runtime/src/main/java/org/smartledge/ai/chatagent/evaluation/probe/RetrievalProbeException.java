package org.smartledge.ai.chatagent.evaluation.probe;

public class RetrievalProbeException extends RuntimeException {

    private final String reasonCode;

    public RetrievalProbeException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
