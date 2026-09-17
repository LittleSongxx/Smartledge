package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEvaluationReport;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEvaluationBatchReport;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEvaluationSuite;

import java.util.List;

public interface GraphRagEvaluationService {

    GraphRagEvaluationReport evaluate(GraphRagEvaluationSuite suite);

    GraphRagEvaluationBatchReport evaluateBatch(String batchId, String name, List<GraphRagEvaluationSuite> suites);
}
