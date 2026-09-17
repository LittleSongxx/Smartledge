package org.smartledge.ai.chatagent.evaluation.probe;

/** Composition-root seam for retrieval probe endpoint limits. */
@FunctionalInterface
public interface RetrievalProbeConfigurationPort {
    RetrievalProbeProperties current();
}
