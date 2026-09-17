package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 视图对象
 * @author: Song
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRouteTraceItemVo {

    private String id;

    private String conversationId;

    private String exchangeId;

    private String question;

    private String rewriteQuestion;

    private String mode;

    private String topScopesJson;

    private String topTopicsJson;

    private String topDocumentsJson;

    private String selectedDocumentId;

    private String hitSelectedDocument;

    private String confidence;

    private String routeStatus;

    private String errorMsg;

    private String createTime;
}
