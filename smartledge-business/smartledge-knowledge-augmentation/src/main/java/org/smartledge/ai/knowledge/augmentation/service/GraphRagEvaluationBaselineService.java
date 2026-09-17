package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEvaluationBatchReport;

public interface GraphRagEvaluationBaselineService {

    GraphRagEvaluationBatchReport evaluateO6LlmNerBaseline();

    GraphRagEvaluationBatchReport evaluateO6CrossDocumentBaseline();
}
