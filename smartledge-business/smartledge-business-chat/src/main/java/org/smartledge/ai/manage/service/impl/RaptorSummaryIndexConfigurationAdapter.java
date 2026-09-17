package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.knowledge.augmentation.port.RaptorSummaryIndexConfigurationPort;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.springframework.stereotype.Service;

/**
 * Composition-root adapter for the RAPTOR summary Elasticsearch index.
 */
@Service
public class RaptorSummaryIndexConfigurationAdapter implements RaptorSummaryIndexConfigurationPort {

    private final DocumentManageProperties properties;

    public RaptorSummaryIndexConfigurationAdapter(DocumentManageProperties properties) {
        this.properties = properties;
    }

    @Override
    public Configuration current() {
        if (properties == null || properties.getElasticsearch() == null) {
            return Configuration.defaults();
        }
        DocumentManageProperties.Elasticsearch elasticsearch = properties.getElasticsearch();
        return new Configuration(
            Boolean.TRUE.equals(elasticsearch.getEnabled()),
            elasticsearch.getRaptorSummaryIndexName(),
            elasticsearch.getAnalyzer(),
            elasticsearch.getSearchAnalyzer()
        );
    }
}
