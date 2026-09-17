package org.smartledge.ai.manage.data;

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
@TableName("smartledge_document_table")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentTable extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long documentId;

    private Long taskId;

    private Long blockId;

    private Integer tableNo;

    private String sectionPath;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String title;

    private Integer rowCount;

    private Integer columnCount;

    private String tableHtml;

    private String metadataJson;
}
