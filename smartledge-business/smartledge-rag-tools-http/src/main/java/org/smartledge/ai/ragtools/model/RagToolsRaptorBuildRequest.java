package org.smartledge.ai.ragtools.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagToolsRaptorBuildRequest {

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
    // Python chunk fields have defaults for omission, but reject explicit null strings/maps.
    @JsonInclude(JsonInclude.Include.NON_NULL)
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
