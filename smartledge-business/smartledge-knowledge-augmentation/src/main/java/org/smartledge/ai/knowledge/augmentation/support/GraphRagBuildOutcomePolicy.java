package org.smartledge.ai.knowledge.augmentation.support;

import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagBuildResult;

import java.util.Objects;

public final class GraphRagBuildOutcomePolicy {

    public GraphRagBuildResult withCrossDocumentOutcome(GraphRagBuildResult result,
                                                        GraphRagBuildResult.ComponentOutcome crossDocumentOutcome) {
        GraphRagBuildResult.GraphRagBuildResultBuilder builder = requireResult(result).toBuilder()
            .crossDocumentIndexOutcome(crossDocumentOutcome);
        if (crossDocumentOutcome == GraphRagBuildResult.ComponentOutcome.FAILED) {
            builder.derivedIndexOutcome(GraphRagBuildResult.DerivedIndexOutcome.FAILED)
                .outerTaskDisposition(GraphRagBuildResult.OuterTaskDisposition.REPAIR_REQUIRED);
        }
        return builder.build();
    }

    public GraphRagBuildResult withObservationOutcome(GraphRagBuildResult result,
                                                      GraphRagBuildResult.ObservationProjectionOutcome observationOutcome) {
        GraphRagBuildResult.GraphRagBuildResultBuilder builder = requireResult(result).toBuilder()
            .observationProjectionOutcome(observationOutcome);
        if (observationOutcome != GraphRagBuildResult.ObservationProjectionOutcome.SUCCESS) {
            builder.outerTaskDisposition(Boolean.TRUE.equals(result.getKgCommitted())
                ? GraphRagBuildResult.OuterTaskDisposition.REPAIR_REQUIRED
                : GraphRagBuildResult.OuterTaskDisposition.FAIL_INDEX_TASK);
        }
        return builder.build();
    }

    public GraphRagBuildResult finalizeOuterDisposition(GraphRagBuildResult result,
                                                        GraphRagBuildResult.ComponentOutcome typedIndexOutcome,
                                                        GraphRagBuildResult.ObservationProjectionOutcome observationOutcome) {
        GraphRagBuildResult current = requireResult(result).toBuilder()
            .typedIndexOutcome(typedIndexOutcome)
            .observationProjectionOutcome(observationOutcome)
            .build();
        GraphRagBuildResult.DerivedIndexOutcome derivedOutcome = aggregateDerived(
            typedIndexOutcome,
            current.getCrossDocumentIndexOutcome()
        );
        GraphRagBuildResult.OuterTaskDisposition disposition;
        if (current.getGraphPersistenceOutcome() == GraphRagBuildResult.GraphPersistenceOutcome.FAILED) {
            disposition = GraphRagBuildResult.OuterTaskDisposition.FAIL_INDEX_TASK;
        }
        else if (derivedOutcome == GraphRagBuildResult.DerivedIndexOutcome.FAILED
            || observationOutcome != GraphRagBuildResult.ObservationProjectionOutcome.SUCCESS) {
            disposition = GraphRagBuildResult.OuterTaskDisposition.REPAIR_REQUIRED;
        }
        else {
            disposition = GraphRagBuildResult.OuterTaskDisposition.CONTINUE;
        }
        return current.toBuilder()
            .derivedIndexOutcome(derivedOutcome)
            .outerTaskDisposition(disposition)
            .build();
    }

    public GraphRagBuildResult preCommitFailure(String reason,
                                                GraphRagBuildResult.InvocationOutcome pythonOutcome,
                                                GraphRagBuildResult.InvocationOutcome advisorOutcome,
                                                java.util.Map<String, Object> extractionMetadata,
                                                int attempt) {
        return GraphRagBuildResult.builder()
            .graphPersistenceOutcome(GraphRagBuildResult.GraphPersistenceOutcome.FAILED)
            .graphPersistenceReason(reason)
            .kgCommitted(false)
            .pythonInvocationOutcome(pythonOutcome)
            .advisorInvocationOutcome(advisorOutcome)
            .extractionMetadata(extractionMetadata)
            .outerTaskDisposition(GraphRagBuildResult.OuterTaskDisposition.FAIL_INDEX_TASK)
            .attempt(attempt)
            .build();
    }

    private GraphRagBuildResult.DerivedIndexOutcome aggregateDerived(
        GraphRagBuildResult.ComponentOutcome typedIndexOutcome,
        GraphRagBuildResult.ComponentOutcome crossDocumentOutcome
    ) {
        if (typedIndexOutcome == GraphRagBuildResult.ComponentOutcome.FAILED
            || crossDocumentOutcome == GraphRagBuildResult.ComponentOutcome.FAILED) {
            return GraphRagBuildResult.DerivedIndexOutcome.FAILED;
        }
        if (typedIndexOutcome == GraphRagBuildResult.ComponentOutcome.SUCCESS
            || crossDocumentOutcome == GraphRagBuildResult.ComponentOutcome.SUCCESS) {
            return GraphRagBuildResult.DerivedIndexOutcome.SUCCESS;
        }
        if (typedIndexOutcome == GraphRagBuildResult.ComponentOutcome.NOT_APPLICABLE
            && crossDocumentOutcome == GraphRagBuildResult.ComponentOutcome.NOT_APPLICABLE) {
            return GraphRagBuildResult.DerivedIndexOutcome.NOT_APPLICABLE;
        }
        return GraphRagBuildResult.DerivedIndexOutcome.FAILED;
    }

    private GraphRagBuildResult requireResult(GraphRagBuildResult result) {
        return Objects.requireNonNull(result, "GraphRAG result is required.");
    }
}
