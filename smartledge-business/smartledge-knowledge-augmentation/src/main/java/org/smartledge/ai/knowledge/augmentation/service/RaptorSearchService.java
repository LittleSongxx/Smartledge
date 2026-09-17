package org.smartledge.ai.knowledge.augmentation.service;

import org.smartledge.ai.knowledge.augmentation.model.raptor.RaptorSearchResult;

import java.util.List;

public interface RaptorSearchService {

    List<RaptorSearchResult> search(String question, List<Long> documentIds, List<Long> taskIds, int topK, int sourceChunkTopK);
}
