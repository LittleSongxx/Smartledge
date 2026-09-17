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
@TableName("smartledge_document_structure_node")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentStructureNode extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long documentId;

    private Long parseTaskId;

    private Integer nodeNo;

    private Integer nodeType;

    private Long parentNodeId;

    private Long prevSiblingNodeId;

    private Long nextSiblingNodeId;

    private Integer depth;

    private String nodeCode;

    private String title;

    private String anchorText;

    private String canonicalPath;

    private String sectionPath;

    private String contentText;

    private Integer itemIndex;

    private String syntaxSchemaVersion;

    private String syntaxSourceSha256;

    private String syntaxNodeId;

    private String syntaxNodeType;

    private String syntaxSourceOrigin;

    private Integer sourceStartByte;

    private Integer sourceEndByte;

    private Integer sourceStartLine;

    private Integer sourceStartColumn;

    private Integer sourceEndLine;

    private Integer sourceEndColumn;
}
