package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagSearchExecution;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchRequest;

public interface GraphRagSearchService {

    GraphRagSearchExecution search(GraphRagSearchRequest request);
}
