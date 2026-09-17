package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagQualityReport;

public interface GraphRagQualityService {

    GraphRagQualityReport evaluate(Long documentId, Long taskId);
}
