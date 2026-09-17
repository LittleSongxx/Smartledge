package org.smartledge.ai.chatagent.evaluation.probe;

import lombok.Builder;
import lombok.Value;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class RetrievalProbeResult {
    public static final String SCHEMA_VERSION = "retrieval-probe-result.v1";

    String schemaVersion;
    String experimentId;
    RetrievalPlan retrievalPlan;
    String retrievalQuestion;
    List<String> usedChannels;
    List<RetrievalProbeSubQuestion> subQuestions;
    Map<String, Object> effectiveConfiguration;
    Map<String, Object> corpusIndexProvenance;
}
