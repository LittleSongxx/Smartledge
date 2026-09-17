package org.smartledge.ai.ragtools.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagToolsGraphCommunityRequest {

    private List<String> nodes = new ArrayList<>();

    private List<Edge> edges = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edge {

        private String source;

        private String target;
    }
}
