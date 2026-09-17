package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;

import java.util.Collection;

public interface KnowledgeBaseRetrievalScopeService {

    KnowledgeBaseSelectionSnapshot resolve(ChatQueryMode chatMode,
                                           KnowledgeBaseSelectionMode selectionMode,
                                           Collection<String> selectedKnowledgeBaseIds);
}
