package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @description: 视图对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentListItemVo {

    private Long documentId;

    private String documentName;

    private String originalFileName;

    private Integer fileType;

    private String fileTypeName;

    private Long fileSize;

    private Integer charCount;

    private Integer tokenCount;

    private Integer parseStatus;

    private String parseStatusName;

    private Integer strategyStatus;

    private String strategyStatusName;

    private Integer indexStatus;

    private String indexStatusName;

    private String parseErrorMsg;

    private Long knowledgeBaseId;

    private String knowledgeBaseName;

    /** JSON object owned by the document lifecycle, exposed for the document detail overview. */
    private String metadataJson;

    private Long currentPlanId;

    private Long lastIndexTaskId;

    private Long latestTaskId;

    private Integer latestTaskType;

    private String latestTaskTypeName;

    private Integer latestTaskStatus;

    private String latestTaskStatusName;

    private Date createTime;

    private Date editTime;

    /** 当前身份是否持有该文档的 ACL MANAGE，用于授权页按钮。 */
    private Boolean canManageAcl;

    /** 对话侧 ACL 是否允许提问（与管理端 read-all 列表可能不一致）。 */
    private Boolean conversationAskable;

    /** 管理端因 read-all 看得见，但对话 ACL 问不到。 */
    private Boolean visibleButNotAskable;
}
