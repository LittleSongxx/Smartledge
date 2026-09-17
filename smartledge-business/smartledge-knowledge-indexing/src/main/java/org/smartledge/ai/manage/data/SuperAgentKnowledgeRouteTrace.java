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

/**
 * @description: 数据实体
 * @author: Song
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("smartledge_knowledge_route_trace")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKnowledgeRouteTrace extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private String conversationId;

    private Long exchangeId;

    private String question;

    private String rewriteQuestion;

    private String mode;

    private String knowledgeBaseSelectionMode;

    private String selectedKnowledgeBaseIdsJson;

    private String selectedKnowledgeBaseNamesJson;

    private String allowedDocumentIdsJson;

    private String topScopesJson;

    private String topTopicsJson;

    private String topDocumentsJson;

    private Long selectedDocumentId;

    private Integer hitSelectedDocument;

    private BigDecimal confidence;

    private Integer routeStatus;

    private String errorMsg;
}
