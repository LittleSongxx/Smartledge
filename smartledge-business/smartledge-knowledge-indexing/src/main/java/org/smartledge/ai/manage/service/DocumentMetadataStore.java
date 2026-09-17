package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.model.DocumentMetadata;

import java.util.Collection;
import java.util.Map;

/**
 * Consumer-owned seam for reading document-level user metadata.
 *
 * <p>The scope resolver is the only production consumer.  Implementations must not source
 * values from chunks, tables, graph artifacts or vector metadata.</p>
 */
public interface DocumentMetadataStore {

    Map<Long, DocumentMetadata> loadByDocumentIds(Collection<Long> documentIds);
}
