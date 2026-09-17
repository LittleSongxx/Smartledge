package org.smartledge.ai.knowledge.augmentation.port;

/**
 * Consumer-owned Elasticsearch index configuration for RAPTOR summaries.
 */
@FunctionalInterface
public interface RaptorSummaryIndexConfigurationPort {

    Configuration current();

    record Configuration(boolean enabled, String indexName, String analyzer, String searchAnalyzer) {

        public static Configuration defaults() {
            return new Configuration(true,
                "smartledge-raptor-summary", "ik_max_word", "ik_smart");
        }
    }
}
