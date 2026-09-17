package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 文档问答路由结果
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentNavigationDecision {

    private DocumentNavigationAction navigationAction;

    private ExecutionMode executionMode;

    private ConversationStructureAnchor structureAnchor;

    private ConversationItemAnchor itemAnchor;

    private QueryUnderstandingResult queryUnderstanding;

    private StructureNavigationResult structureNavigationResult;

    @Builder.Default
    private RetrievalIntent retrievalIntent = RetrievalIntent.GENERAL;

    private String summaryText;

    @Builder.Default
    private List<String> queryContextHints = new ArrayList<>();

    @Builder.Default
    private List<String> softSectionHints = new ArrayList<>();
}
