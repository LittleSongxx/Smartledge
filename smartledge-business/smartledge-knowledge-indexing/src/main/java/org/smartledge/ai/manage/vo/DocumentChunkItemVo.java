package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 视图对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunkItemVo {

    private Long chunkId;

    private Long parentBlockId;

    private Integer parentBlockNo;

    private Integer parentChildCount;

    private Integer parentStartChunkNo;

    private Integer parentEndChunkNo;

    private Integer chunkNo;

    private String sectionPath;

    private Integer sourceType;

    private String sourceTypeName;

    private Integer charCount;

    private Integer tokenCount;

    private Integer vectorStatus;

    private String vectorStatusName;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String sourceBlockIds;

    private String contentWithWeight;

    private String chunkType;

    private String title;

    private String keywords;

    private String questions;

    private String chunkText;

    private String sourceProvenanceJson;
}
