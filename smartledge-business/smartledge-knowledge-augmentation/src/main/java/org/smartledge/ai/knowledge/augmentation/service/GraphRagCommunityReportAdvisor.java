package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagCommunityReportAdvice;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagCommunityReportContext;

import java.util.Optional;

public interface GraphRagCommunityReportAdvisor {

    Optional<GraphRagCommunityReportAdvice> generateReport(GraphRagCommunityReportContext context);
}
