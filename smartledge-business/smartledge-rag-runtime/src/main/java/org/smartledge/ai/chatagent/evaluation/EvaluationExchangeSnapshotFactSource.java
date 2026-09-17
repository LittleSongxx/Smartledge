package org.smartledge.ai.chatagent.evaluation;

import java.util.List;
import java.util.Map;

interface EvaluationExchangeSnapshotFactSource {

    Map<Long, EvaluationExchangeSnapshotFacts> load(List<Long> exchangeIds);
}
