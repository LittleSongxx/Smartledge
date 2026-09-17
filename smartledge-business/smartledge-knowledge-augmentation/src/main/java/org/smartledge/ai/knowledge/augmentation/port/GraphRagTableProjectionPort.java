package org.smartledge.ai.knowledge.augmentation.port;

import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionRequest;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;

import java.util.List;
import java.util.Map;

/** Supplies persisted table structure keyed by the frozen source chunk that contains it. */
public interface GraphRagTableProjectionPort {

    Map<Long, List<GraphRagExtractionRequest.StructuredTable>> load(Long documentId,
            Long sourceParseTaskId, List<SuperAgentDocumentChunk> chunks);
}
