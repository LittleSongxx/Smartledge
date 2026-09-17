package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEntityResolutionAdvice;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEntityResolutionContext;

import java.util.Optional;

public interface GraphRagEntityResolutionAdvisor {

    Optional<GraphRagEntityResolutionAdvice> advise(GraphRagEntityResolutionContext context);
}
