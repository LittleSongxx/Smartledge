package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.manage.service.DocumentNavigationIndexService;
import org.smartledge.ai.rag.runtime.port.DocumentNavigationIndexPort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentNavigationIndexPortAdapter implements DocumentNavigationIndexPort {

    private final DocumentNavigationIndexService delegate;

    public DocumentNavigationIndexPortAdapter(DocumentNavigationIndexService delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<NavigationSectionHit> searchSections(Long documentId,
                                                     String topic,
                                                     String facet,
                                                     String informationNeed,
                                                     String question,
                                                     int size) {
        return delegate.searchSections(documentId, topic, facet, informationNeed, question, size).stream()
            .map(hit -> new NavigationSectionHit(hit.nodeId(), hit.nodeCode(), hit.title(), hit.sectionPath(), hit.canonicalPath(), hit.score()))
            .toList();
    }
}
