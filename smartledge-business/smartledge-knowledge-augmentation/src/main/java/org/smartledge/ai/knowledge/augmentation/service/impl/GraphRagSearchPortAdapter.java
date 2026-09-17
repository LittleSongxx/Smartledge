package org.smartledge.ai.knowledge.augmentation.service.impl;

import org.smartledge.ai.knowledge.augmentation.service.GraphRagSearchService;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchResult;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchRequest;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchResponse;
import org.smartledge.ai.rag.runtime.port.GraphRagSearchPort;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GraphRagSearchPortAdapter implements GraphRagSearchPort {

    private final GraphRagSearchService delegate;

    public GraphRagSearchPortAdapter(GraphRagSearchService delegate) {
        this.delegate = delegate;
    }

    @Override
    public GraphRagSearchResponse search(GraphRagSearchRequest request) {
        var execution = delegate.search(request);
        List<GraphRagSearchResult> results = execution.results().stream().map(source -> {
            GraphRagSearchResult target = new GraphRagSearchResult();
            BeanUtils.copyProperties(source, target);
            return target;
        }).toList();
        return new GraphRagSearchResponse(results, execution.observation());
    }
}
