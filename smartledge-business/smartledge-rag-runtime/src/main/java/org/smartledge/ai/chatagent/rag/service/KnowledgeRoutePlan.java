package org.smartledge.ai.chatagent.rag.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.chatagent.rag.model.RouteScopeAuthorizationMode;
import org.smartledge.ai.rag.runtime.model.route.KnowledgeRouteDecision;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 知识路由建议与业务范围授权结果；推荐 hint 不拥有执行范围
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRoutePlan {

    private KnowledgeRouteDecision routeDecision;

    private RouteScopeAuthorizationMode authorizationMode;

    private String scopeAuthorizationReason;

    private boolean clarificationRequired;

    private String clarificationReply;

    @Builder.Default
    private List<String> clarificationOptions = new ArrayList<>();

    private String clarificationReason;

    private Long recommendedDocumentId;

    private String recommendedDocumentName;

    private Long recommendedTaskId;

    @Builder.Default
    private List<Long> authorizedDocumentIds = new ArrayList<>();

    @Builder.Default
    private List<Long> authorizedTaskIds = new ArrayList<>();
}
