package org.smartledge.ai.manage.model.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 知识路由索引记录
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRouteIndexRecord {

    private String routeId;

    private String entityType;

    private Long entityId;

    private Long documentId;

    private Long knowledgeBaseId;

    private Long scopeId;

    private String scopeName;

    private Long topicId;

    private String topicName;

    private String documentName;

    private String displayName;

    private String descriptionText;

    private String aliasesText;

    private String examplesText;

    private String summaryText;

    private String routeText;

    @Builder.Default
    private List<String> entityTerms = new ArrayList<>();

    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
