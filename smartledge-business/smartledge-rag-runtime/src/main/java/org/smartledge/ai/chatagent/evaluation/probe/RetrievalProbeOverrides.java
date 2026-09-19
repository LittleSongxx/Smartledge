package org.smartledge.ai.chatagent.evaluation.probe;

import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class RetrievalProbeOverrides {

    private Integer candidateTopK;

    private Integer rerankCandidateTopK;

    private Integer finalTopK;

    private Boolean rerankEnabled;

    /** 最终证据最低置信度（0-1，rerank 分口径；0=关闭），用于阈值校准实验。 */
    private Double minEvidenceConfidence;

    private List<String> enabledChannels = new ArrayList<>();

    /** Explicitly represented so build-time changes fail closed instead of being mistaken for query-time tuning. */
    private Map<String, Object> buildParameters = new LinkedHashMap<>();

    @JsonIgnore
    private final Map<String, Object> unknownProperties = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknownProperty(String name, Object value) {
        unknownProperties.put(name, value);
    }
}
