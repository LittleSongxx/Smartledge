package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagQueryCatalog;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagQueryPlanAdvice;

import java.util.Optional;

public interface GraphRagQueryPlanAdvisor {

    Optional<GraphRagQueryPlanAdvice> advise(String question, GraphRagQueryCatalog catalog);
}
