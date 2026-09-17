package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.manage.service.KnowledgeBaseManageService;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseOption;
import org.smartledge.ai.rag.runtime.port.KnowledgeBaseCatalogPort;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeBaseCatalogPortAdapter implements KnowledgeBaseCatalogPort {

    private final KnowledgeBaseManageService delegate;

    public KnowledgeBaseCatalogPortAdapter(KnowledgeBaseManageService delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<KnowledgeBaseOption> listOptions() {
        return delegate.listOptions().stream().map(source -> {
            KnowledgeBaseOption target = new KnowledgeBaseOption();
            BeanUtils.copyProperties(source, target);
            return target;
        }).toList();
    }
}
