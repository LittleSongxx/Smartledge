package org.smartledge.ai.chatagent.evaluation;

/** Composition-root seam for evaluation snapshot endpoint limits. */
@FunctionalInterface
public interface EvaluationSnapshotConfigurationPort {
    EvaluationSnapshotProperties current();
}
