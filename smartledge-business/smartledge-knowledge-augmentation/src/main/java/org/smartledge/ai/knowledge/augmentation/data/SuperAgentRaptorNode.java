package org.smartledge.ai.knowledge.augmentation.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_raptor_node")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentRaptorNode extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long documentId;

    private Long taskId;

    private String scopeType;

    private String scopeKey;

    private String nodeKey;

    private Long parentNodeId;

    private Integer nodeLevel;

    private Integer nodeNo;

    private String title;

    private String summary;

    private String summaryWithWeight;

    private String childNodeIdsJson;

    private String sourceChunkIdsJson;

    private String sourceParentBlockIdsJson;

    private String sourceDocumentIdsJson;

    private String sourceTaskIdsJson;

    private String sectionPath;

    private String pageRange;

    private String keywords;

    private String questions;

    private String metadataJson;
}
