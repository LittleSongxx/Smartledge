package org.smartledge.ai.knowledge.augmentation.service.impl;

import org.smartledge.ai.knowledge.augmentation.service.RaptorSearchService;
import org.smartledge.ai.rag.runtime.model.raptor.RaptorSearchResult;
import org.smartledge.ai.rag.runtime.port.RaptorSearchPort;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RaptorSearchPortAdapter implements RaptorSearchPort {

    private final RaptorSearchService delegate;

    public RaptorSearchPortAdapter(RaptorSearchService delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<RaptorSearchResult> search(String question, List<Long> documentIds, List<Long> taskIds, int topK, int sourceChunkTopK) {
        return delegate.search(question, documentIds, taskIds, topK, sourceChunkTopK).stream().map(source -> {
            RaptorSearchResult target = new RaptorSearchResult();
            BeanUtils.copyProperties(source, target);
            return target;
        }).toList();
    }
}
