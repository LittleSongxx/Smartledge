package org.smartledge.ai.knowledge.augmentation.support;

import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionRequest;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionResponse;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates the complete execution projection before Java candidate grounding. */
public final class GraphRagExtractionContractValidator {
    public static final String SCHEMA_VERSION = GraphRagCandidateExecution.VERSION;
    public static final String OBSERVATION_WARNINGS_KEY = "contractObservationWarnings";

    public ValidatedContract validate(GraphRagExtractionResponse response, GraphRagExtractionRequest request) {
        if (response == null || response.getMetadata() == null || response.getEntities() == null
                || response.getRelations() == null || response.getEvidences() == null
                || response.getCommunities() == null) {
            throw invalid("incomplete response");
        }
        Map<String, Object> metadata = response.getMetadata();
        if (!Objects.equals(metadata.get("schemaVersion"), SCHEMA_VERSION)
                || !Objects.equals(metadata.get("status"), "completed") || !response.getCommunities().isEmpty()) {
            throw invalid("version/status mismatch");
        }
        int count = request.getChunks().size();
        if (!Objects.equals(metadata.get("plannedSourceCount"), count)
                || !Objects.equals(metadata.get("completedSourceCount"), count)
                || !Objects.equals(metadata.get("failedSourceCount"), 0)
                || !Objects.equals(metadata.get("unprocessedSourceCount"), 0)) {
            throw invalid("source coverage mismatch");
        }
        return new ValidatedContract("completed", List.of(), count, response.getEntities().size(),
                response.getEvidences().size(), metadata, List.of());
    }

    private ContractException invalid(String reason) {
        return new ContractException("FAILED_CONTRACT[" + SCHEMA_VERSION + "]: " + reason);
    }

    public record ValidatedContract(String status, List<String> reasonCodes, int validChunkCount, int entityCount,
            int evidenceCount, Map<String, Object> metadata, List<String> observationWarnings) {
        public boolean degraded() {
            return false;
        }

        public boolean reportedDegraded() {
            return false;
        }

        public boolean observationDegraded() {
            return false;
        }

        public boolean empty() {
            return entityCount == 0;
        }
    }

    public static final class ContractException extends IllegalArgumentException {
        private ContractException(String message) {
            super(message);
        }
    }
}
