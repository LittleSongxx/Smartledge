package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.KnowledgeBaseOption;

import java.util.List;

public interface KnowledgeBaseCatalogPort {

    List<KnowledgeBaseOption> listOptions();
}
