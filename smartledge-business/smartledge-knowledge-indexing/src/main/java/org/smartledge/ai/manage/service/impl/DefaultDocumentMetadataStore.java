package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.model.DocumentMetadata;
import org.smartledge.ai.manage.service.DocumentMetadataStore;
import org.smartledge.ai.manage.support.DocumentMetadataJsonParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** MyBatis adapter for the document-owned metadata column. */
@Service
public class DefaultDocumentMetadataStore implements DocumentMetadataStore {

    private final SuperAgentDocumentMapper documentMapper;
    private final DocumentMetadataJsonParser metadataParser;

    @Autowired
    public DefaultDocumentMetadataStore(SuperAgentDocumentMapper documentMapper) {
        this(documentMapper, new DocumentMetadataJsonParser());
    }

    DefaultDocumentMetadataStore(SuperAgentDocumentMapper documentMapper,
                                 DocumentMetadataJsonParser metadataParser) {
        this.documentMapper = documentMapper;
        this.metadataParser = metadataParser;
    }

    @Override
    public Map<Long, DocumentMetadata> loadByDocumentIds(Collection<Long> documentIds) {
        List<Long> ids = documentIds == null
            ? List.of()
            : documentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, DocumentMetadata> result = new LinkedHashMap<>();
        for (SuperAgentDocument document : documentMapper.selectBatchIds(ids)) {
            if (document == null || document.getId() == null) {
                continue;
            }
            try {
                result.put(document.getId(), new DocumentMetadata(
                    document.getId(), metadataParser.parse(document.getMetadataJson()), true));
            }
            catch (RuntimeException exception) {
                result.put(document.getId(), DocumentMetadata.invalid(document.getId()));
            }
        }
        return Map.copyOf(result);
    }
}
