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
@TableName("smartledge_document_parse_artifact")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentParseArtifact extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private Long documentId;

    private Long taskId;

    private String artifactType;

    private String objectName;

    private String contentHash;

    private String parserName;

    private String parserVersion;
}
