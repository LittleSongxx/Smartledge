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
@TableName("smartledge_kg_evidence")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKgEvidence extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long documentId;

    private Long taskId;

    private Long entityId;

    private Long relationId;

    private Long chunkId;

    private Long parentBlockId;

    private String quoteText;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String sectionPath;

    private String metadataJson;
}
