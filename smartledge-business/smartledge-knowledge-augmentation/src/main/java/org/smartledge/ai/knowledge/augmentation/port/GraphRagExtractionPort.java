package org.smartledge.ai.knowledge.augmentation.port;

import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionRequest;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionResponse;

public interface GraphRagExtractionPort {

    GraphRagExtractionResponse extract(GraphRagExtractionRequest request);
}
