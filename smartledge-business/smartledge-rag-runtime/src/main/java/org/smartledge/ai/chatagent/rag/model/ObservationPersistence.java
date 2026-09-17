package org.smartledge.ai.chatagent.rag.model;

import java.util.Objects;

/** Outcome of one sub-question's raw retrieval-observation batch. */
public record ObservationPersistence(
    String schemaVersion,
    Status status,
    int expectedCandidateCount,
    int persistedCandidateCount,
    ErrorType errorType
) {

    public static final String SCHEMA_VERSION = "retrieval-observation-persistence.v1";

    public ObservationPersistence {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported retrieval observation persistence schema");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(errorType, "errorType");
        if (expectedCandidateCount < 0 || persistedCandidateCount < 0) {
            throw new IllegalArgumentException("Observation candidate counts must be non-negative");
        }
        switch (status) {
            case SUCCESS -> {
                if (expectedCandidateCount != persistedCandidateCount || errorType != ErrorType.NONE) {
                    throw new IllegalArgumentException(
                        "Successful observation persistence must conserve candidate count without an error"
                    );
                }
            }
            case FAILED -> {
                if (errorType == ErrorType.NONE || errorType == ErrorType.NO_RECORDER) {
                    throw new IllegalArgumentException(
                        "Failed observation persistence requires an attempted failure error type"
                    );
                }
            }
            case NOT_ATTEMPTED -> {
                if (persistedCandidateCount != 0 || errorType != ErrorType.NO_RECORDER) {
                    throw new IllegalArgumentException(
                        "A non-attempted observation batch requires the no-recorder boundary and zero persisted rows"
                    );
                }
            }
        }
    }

    public static ObservationPersistence success(int candidateCount) {
        return new ObservationPersistence(
            SCHEMA_VERSION,
            Status.SUCCESS,
            candidateCount,
            candidateCount,
            ErrorType.NONE
        );
    }

    public static ObservationPersistence failed(int expectedCandidateCount,
                                                int persistedCandidateCount,
                                                ErrorType errorType) {
        return new ObservationPersistence(
            SCHEMA_VERSION,
            Status.FAILED,
            expectedCandidateCount,
            persistedCandidateCount,
            errorType
        );
    }

    public static ObservationPersistence failed(int expectedCandidateCount, ErrorType errorType) {
        return failed(expectedCandidateCount, 0, errorType);
    }

    public static ObservationPersistence notAttempted(int expectedCandidateCount, ErrorType errorType) {
        return new ObservationPersistence(
            SCHEMA_VERSION,
            Status.NOT_ATTEMPTED,
            expectedCandidateCount,
            0,
            errorType
        );
    }

    public enum Status {
        SUCCESS,
        FAILED,
        NOT_ATTEMPTED
    }

    public enum ErrorType {
        NONE,
        NO_RECORDER,
        RETRIEVAL_FAILED,
        OBSERVATION_PROJECTION_ERROR,
        DATA_INTEGRITY_VIOLATION,
        VALIDATION_ERROR,
        ROW_COUNT_MISMATCH,
        PERSISTENCE_ERROR
    }
}
