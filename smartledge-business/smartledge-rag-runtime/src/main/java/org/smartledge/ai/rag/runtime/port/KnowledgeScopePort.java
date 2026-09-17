package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;

import java.util.Collection;

public interface KnowledgeScopePort {

    KnowledgeBaseSelectionSnapshot resolve(ChatQueryMode chatMode,
                                           KnowledgeBaseSelectionMode selectionMode,
                                           Collection<String> selectedKnowledgeBaseIds);
}
