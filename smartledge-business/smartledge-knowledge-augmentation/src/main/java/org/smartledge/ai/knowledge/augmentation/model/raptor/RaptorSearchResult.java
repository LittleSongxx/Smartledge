package org.smartledge.ai.knowledge.augmentation.model.raptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaptorSearchResult {

    private Long documentId;

    private Long taskId;

    private Long raptorNodeId;

    private String raptorNodeTitle;

    private Integer raptorNodeLevel;

    private String raptorSummary;

    private String sourceStatus;

    private Long chunkId;

    private Long parentBlockId;

    private Integer chunkNo;

    private String chunkText;

    private String title;

    private String sectionPath;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String sourceBlockIds;

    private Double score;

    /**
     * RAPTOR 内部弱词面 rank feature（P8/P11）：summary 节点下钻 source chunk 时的词面 boost 分量。
     * 仅用于观测（O9）与内部排序解释，不影响 citation 身份；summary-only 结果为 0。
     */
    private Double rankFeatureBoost;
}
