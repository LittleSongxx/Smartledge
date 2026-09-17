package org.smartledge.ai.manage.support;

import org.smartledge.ai.knowledge.indexing.port.DocumentStorageConfigurationPort;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.springframework.stereotype.Component;

/** Composition-root adapter for object-storage settings. */
@Component
public final class DocumentManageStorageConfigurationAdapter implements DocumentStorageConfigurationPort {

    private final DocumentManageProperties properties;

    public DocumentManageStorageConfigurationAdapter(DocumentManageProperties properties) {
        this.properties = properties == null ? new DocumentManageProperties() : properties;
    }

    @Override
    public String endpoint() { return storage().getEndpoint(); }

    @Override
    public String bucketName() { return storage().getBucketName(); }

    @Override
    public String objectPrefix() { return storage().getObjectPrefix(); }

    @Override
    public String parsedTextPrefix() { return storage().getParsedTextPrefix(); }

    @Override
    public String parseArtifactPrefix() { return storage().getParseArtifactPrefix(); }

    private DocumentManageProperties.Minio storage() {
        DocumentManageProperties.Minio storage = properties.getMinio();
        return storage == null ? new DocumentManageProperties.Minio() : storage;
    }
}
