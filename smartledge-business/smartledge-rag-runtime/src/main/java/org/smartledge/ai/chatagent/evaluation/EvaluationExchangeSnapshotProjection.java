package org.smartledge.ai.chatagent.evaluation;

import java.util.List;

/**
 * Consumer-owned interface for exporting immutable, same-turn evaluation facts.
 * Implementations must only project already archived facts and must never retrieve or generate again.
 */
public interface EvaluationExchangeSnapshotProjection {

    List<EvaluationExchangeSnapshot> query(EvaluationExchangeSnapshotQuery query);
}
