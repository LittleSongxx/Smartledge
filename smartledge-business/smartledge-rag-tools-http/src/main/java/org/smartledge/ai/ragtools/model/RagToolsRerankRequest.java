package org.smartledge.ai.ragtools.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagToolsRerankRequest {

    private String query;

    private List<Candidate> candidates;

    private Integer topK;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate {

        private String id;

        private String text;

        private Map<String, Object> metadata = new LinkedHashMap<>();
    }
}
