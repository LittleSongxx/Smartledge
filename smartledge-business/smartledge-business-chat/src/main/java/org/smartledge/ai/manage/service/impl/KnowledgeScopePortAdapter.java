package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.rag.runtime.port.KnowledgeScopePort;
import org.smartledge.ai.manage.service.KnowledgeBaseRetrievalScopeService;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
public class KnowledgeScopePortAdapter implements KnowledgeScopePort {

    private final KnowledgeBaseRetrievalScopeService delegate;

    public KnowledgeScopePortAdapter(KnowledgeBaseRetrievalScopeService delegate) {
        this.delegate = delegate;
    }

    @Override
    public KnowledgeBaseSelectionSnapshot resolve(ChatQueryMode chatMode,
                                                  KnowledgeBaseSelectionMode selectionMode,
                                                  Collection<String> selectedKnowledgeBaseIds) {
        org.smartledge.ai.manage.model.KnowledgeBaseSelectionSnapshot source = delegate.resolve(chatMode, selectionMode, selectedKnowledgeBaseIds);
        KnowledgeBaseSelectionSnapshot target = new KnowledgeBaseSelectionSnapshot();
        target.setSelectionMode(source.getSelectionMode());
        target.setSelectedKnowledgeBaseIds(source.getSelectedKnowledgeBaseIds());
        target.setSelectedKnowledgeBaseNames(source.getSelectedKnowledgeBaseNames());
        target.setAllowedDocumentIds(source.getAllowedDocumentIds());
        target.setAllowedTaskIds(source.getAllowedTaskIds());
        target.setRagRuntimeOptions(source.getRagRuntimeOptions());
        target.setAllowedDocuments(source.getAllowedDocuments().stream().map(item -> {
            KnowledgeDocumentDescriptor descriptor = new KnowledgeDocumentDescriptor();
            descriptor.setDocumentId(item.getDocumentId());
            descriptor.setDocumentName(item.getDocumentName());
            descriptor.setLastIndexTaskId(item.getLastIndexTaskId());
            descriptor.setKnowledgeBaseId(item.getKnowledgeBaseId());
            descriptor.setKnowledgeBaseName(item.getKnowledgeBaseName());
            return descriptor;
        }).toList());
        return target;
    }
}
