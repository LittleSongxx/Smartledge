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
public class RaptorBuildRequest {

    private Long documentId;

    private Long taskId;

    private Integer maxClusterSize;

    private Integer maxLevels;

    private Boolean llmSummaryEnabled;

    private Integer llmConcurrency;

    private List<Chunk> chunks = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Chunk {

        private Long chunkId;

        private Long parentBlockId;

        private Integer chunkNo;

        private String chunkType;

        private String title;

        private String sectionPath;

        private Integer pageNo;

        private String pageRange;

        private String bboxJson;

        private String text;

        private String contentWithWeight;

        private String sourceBlockIds;

        private Map<String, Object> metadata;
    }
}
