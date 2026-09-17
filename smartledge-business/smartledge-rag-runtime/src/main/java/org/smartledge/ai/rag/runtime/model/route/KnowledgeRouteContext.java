package org.smartledge.ai.rag.runtime.model.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.enums.KnowledgeBaseSelectionMode;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRouteContext {

    private String question;

    private String rewriteQuestion;

    @Builder.Default
    private KnowledgeBaseSelectionMode knowledgeBaseSelectionMode = KnowledgeBaseSelectionMode.NONE;

    @Builder.Default
    private List<Long> selectedKnowledgeBaseIds = new ArrayList<>();

    @Builder.Default
    private List<String> selectedKnowledgeBaseNames = new ArrayList<>();

    @Builder.Default
    private List<KnowledgeDocumentDescriptor> allowedDocuments = new ArrayList<>();

    @Builder.Default
    private List<Long> allowedDocumentIds = new ArrayList<>();
}
