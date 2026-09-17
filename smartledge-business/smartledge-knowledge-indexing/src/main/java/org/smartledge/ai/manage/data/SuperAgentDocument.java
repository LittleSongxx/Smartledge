package org.smartledge.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.smartledge.database.data.BaseTableData;

import java.time.LocalDateTime;

/**
 * @description: 数据实体
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_document")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocument extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private String documentName;

    private String originalFileName;

    private Integer fileType;

    private String mimeType;

    private Long fileSize;

    private Integer storageType;

    private String bucketName;

    private String objectName;

    private String objectUrl;

    private Integer parseStatus;

    private Integer strategyStatus;

    private Integer indexStatus;

    private Integer charCount;

    private Integer tokenCount;

    private Integer structureLevel;

    private Integer contentQualityLevel;

    private String parseTextPath;

    private String parseErrorMsg;

    private Long knowledgeBaseId;

    private String knowledgeBaseName;

    /** JSON object owned by the document lifecycle; distinct from generated chunk metadata. */
    private String metadataJson;

    private String contentHash;

    private String sourceUri;

    private String language;

    private LocalDateTime effectiveFrom;

    private LocalDateTime expiresAt;

    private Long currentPlanId;

    private Long lastParseTaskId;

    private Integer structureNodeCount;

    private Long lastIndexTaskId;
}
