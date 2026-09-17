package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.raptor.RaptorSearchResult;

import java.util.List;

public interface RaptorSearchPort {

    List<RaptorSearchResult> search(String question,
                                    List<Long> documentIds,
                                    List<Long> taskIds,
                                    int topK,
                                    int sourceChunkTopK);
}
