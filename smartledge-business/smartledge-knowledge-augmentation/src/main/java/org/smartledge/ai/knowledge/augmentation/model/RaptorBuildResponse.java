package org.smartledge.ai.knowledge.augmentation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RaptorBuildResponse {

    private List<Node> nodes = new ArrayList<>();

    private Map<String, Object> metadata;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Node {

        private String id;

        private String parentId;

        private Integer level;

        private Integer nodeNo;

        private String title;

        private String summary;

        private String summaryWithWeight;

        private List<String> childNodeIds = new ArrayList<>();

        private List<Long> sourceChunkIds = new ArrayList<>();

        private List<Long> sourceParentBlockIds = new ArrayList<>();

        private String sectionPath;

        private String pageRange;

        private List<String> keywords = new ArrayList<>();

        private List<String> questions = new ArrayList<>();

        private Double qualityScore;

        private Map<String, Object> metadata;
    }
}
