package org.smartledge.ai.knowledge.augmentation.port;

import org.smartledge.ai.knowledge.augmentation.model.RaptorBuildRequest;
import org.smartledge.ai.knowledge.augmentation.model.RaptorBuildResponse;

public interface RaptorBuildPort {

    RaptorBuildResponse build(RaptorBuildRequest request);
}
