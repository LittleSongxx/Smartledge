package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchRequest;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchResponse;

public interface GraphRagSearchPort {

    GraphRagSearchResponse search(GraphRagSearchRequest request);
}
