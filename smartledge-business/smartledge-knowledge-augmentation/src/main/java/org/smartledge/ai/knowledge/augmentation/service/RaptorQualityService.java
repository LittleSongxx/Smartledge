package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.knowledge.augmentation.data.SuperAgentRaptorNode;
import org.smartledge.ai.knowledge.augmentation.model.raptor.RaptorQualityReport;
import org.smartledge.ai.knowledge.augmentation.model.RaptorBuildResponse;

import java.util.List;

public interface RaptorQualityService {

    RaptorQualityReport evaluate(Long documentId, Long taskId);

    RaptorQualityReport evaluate(List<SuperAgentRaptorNode> nodes, double configuredFloor);

    RaptorQualityReport evaluatePythonNodes(List<RaptorBuildResponse.Node> nodes, double configuredFloor);
}
