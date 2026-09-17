package org.smartledge.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_document_table_cell")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentTableCell extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long documentId;

    private Long taskId;

    private Long tableId;

    private Long rowId;

    private Long columnId;

    private Integer rowNo;

    private Integer columnNo;

    private String cellText;

    private BigDecimal numericValue;

    private Integer sourceRowNo;

    private Integer sourceColumnNo;

    private String sourceCellRef;

    private String bboxJson;

    private String metadataJson;
}
