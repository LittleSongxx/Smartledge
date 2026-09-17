package org.smartledge.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

/**
 * @description: 数据实体
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_document_chunk")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentChunk extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long documentId;

    private Long taskId;

    private Long planId;

    private Long parentBlockId;

    private Integer chunkNo;

    private Integer sourceType;

    private String sectionPath;

    private Long structureNodeId;

    private Integer structureNodeType;

    private String canonicalPath;

    private Integer itemIndex;

    private String chunkText;

    private String contentWithWeight;

    private String chunkType;

    private String title;

    private String keywords;

    private String questions;

    private Integer charCount;

    private Integer tokenCount;

    private Integer vectorStatus;

    private Integer vectorStoreType;

    private String vectorId;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String sourceBlockIds;

    private String sourceProvenanceJson;
}
