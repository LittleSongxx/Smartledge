package org.smartledge.ai.chatagent.evaluation.probe;

import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.smartledge.enums.KnowledgeBaseSelectionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class RetrievalProbeQuery {

    public static final String SCHEMA_VERSION = "retrieval-probe-query.v1";

    private String schemaVersion;

    private String experimentId;

    private String query;

    private KnowledgeBaseSelectionMode selectionMode = KnowledgeBaseSelectionMode.SELECTED;

    private List<String> knowledgeBaseIds = new ArrayList<>();

    private Long documentId;

    private RetrievalProbeOverrides overrides = new RetrievalProbeOverrides();

    @JsonIgnore
    private final Map<String, Object> unknownProperties = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknownProperty(String name, Object value) {
        unknownProperties.put(name, value);
    }
}
