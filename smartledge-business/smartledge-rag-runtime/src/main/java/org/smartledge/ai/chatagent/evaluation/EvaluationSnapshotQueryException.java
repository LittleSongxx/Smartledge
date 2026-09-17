package org.smartledge.ai.chatagent.evaluation;

public class EvaluationSnapshotQueryException extends RuntimeException {

    private final String reasonCode;

    public EvaluationSnapshotQueryException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
