package org.smartledge.ai.chatagent.rag.service;

import org.smartledge.ai.rag.runtime.model.RagRuntimeOptions;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @description: 知识库硬边界纯访问器，从会话选择快照读取执行身份
 * @author: Song
 **/

@Service
public class KnowledgeBoundaryResolver {

    public RagRuntimeOptions runtimeOptions(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getRagRuntimeOptions() == null
            ? RagRuntimeOptions.defaults()
            : knowledgeBaseSelection.getRagRuntimeOptions();
    }

    public KnowledgeBaseSelectionMode selectionMode(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getSelectionMode() == null
            ? KnowledgeBaseSelectionMode.NONE
            : knowledgeBaseSelection.getSelectionMode();
    }

    public List<Long> selectedKnowledgeBaseIds(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getSelectedKnowledgeBaseIds() == null
            ? List.of()
            : knowledgeBaseSelection.getSelectedKnowledgeBaseIds();
    }

    public List<String> selectedKnowledgeBaseNames(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getSelectedKnowledgeBaseNames() == null
            ? List.of()
            : knowledgeBaseSelection.getSelectedKnowledgeBaseNames();
    }

    public List<KnowledgeDocumentDescriptor> allowedDocuments(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getAllowedDocuments() == null
            ? List.of()
            : knowledgeBaseSelection.getAllowedDocuments();
    }

    public List<Long> allowedDocumentIds(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getAllowedDocumentIds() == null
            ? List.of()
            : knowledgeBaseSelection.getAllowedDocumentIds();
    }

    public List<Long> allowedTaskIds(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getAllowedTaskIds() == null
            ? List.of()
            : knowledgeBaseSelection.getAllowedTaskIds();
    }
}
