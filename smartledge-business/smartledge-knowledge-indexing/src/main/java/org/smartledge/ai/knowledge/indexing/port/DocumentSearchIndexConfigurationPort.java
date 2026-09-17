package org.smartledge.ai.knowledge.indexing.port;

/**
 * Consumer-owned seam for the Elasticsearch indexes used by document
 * navigation and knowledge-route indexing.
 *
 * <p>The implementation does not know how the names are bound or refreshed;
 * those concerns stay in the composition root.</p>
 */
@FunctionalInterface
public interface DocumentSearchIndexConfigurationPort {

    IndexSettings currentIndexSettings();

    record IndexSettings(String keywordIndexName,
                         String navigationIndexName,
                         String routeIndexName,
                         Boolean refreshWait) {

        public static IndexSettings defaults() {
            return new IndexSettings(
                "smartledge-document-keyword",
                "smartledge-document-navigation",
                "smartledge-knowledge-route",
                Boolean.TRUE
            );
        }
    }
}
