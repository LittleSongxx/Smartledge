package org.smartledge.ai.knowledge.indexing.port;

/**
 * Consumer-owned object-storage configuration seam for indexing artifacts.
 */
public interface DocumentStorageConfigurationPort {

    String endpoint();

    String bucketName();

    String objectPrefix();

    String parsedTextPrefix();

    String parseArtifactPrefix();

    static DocumentStorageConfigurationPort defaults() {
        return new DocumentStorageConfigurationPort() {
            @Override
            public String endpoint() { return "http://127.0.0.1:9000"; }

            @Override
            public String bucketName() { return "smartledge-document"; }

            @Override
            public String objectPrefix() { return "rag/document"; }

            @Override
            public String parsedTextPrefix() { return "rag/parsed-text"; }

            @Override
            public String parseArtifactPrefix() { return "rag/parse-artifact"; }
        };
    }
}
