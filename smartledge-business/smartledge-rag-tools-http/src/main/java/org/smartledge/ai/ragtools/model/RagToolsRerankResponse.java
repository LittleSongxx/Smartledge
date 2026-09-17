package org.smartledge.ai.ragtools.model;

import lombok.Data;

import java.util.List;

@Data
public class RagToolsRerankResponse {

    private List<Result> results;

    @Data
    public static class Result {

        private String id;

        private Double score;

        private Integer rank;
    }
}
