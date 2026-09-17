package org.smartledge.ai.chatagent.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record EvaluationExchangeSnapshotQuery(
    String schemaVersion,
    List<Long> exchangeIds,
    boolean includeDiagnostics
) {

    public EvaluationExchangeSnapshotQuery {
        exchangeIds = exchangeIds == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(exchangeIds));
    }
}
