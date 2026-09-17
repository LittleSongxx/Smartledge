package org.smartledge.ai.manage.support;

import org.smartledge.ai.knowledge.indexing.port.DocumentSearchIndexConfigurationPort;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.springframework.stereotype.Component;

/** Composition-root adapter for document Elasticsearch index settings. */
@Component
public final class DocumentManageSearchIndexConfigurationAdapter implements DocumentSearchIndexConfigurationPort {

    private final DocumentManageProperties properties;

    public DocumentManageSearchIndexConfigurationAdapter(DocumentManageProperties properties) {
        this.properties = properties == null ? new DocumentManageProperties() : properties;
    }

    @Override
    public IndexSettings currentIndexSettings() {
        DocumentManageProperties.Elasticsearch elasticsearch = properties.getElasticsearch();
        DocumentManageProperties.IndexBuild indexBuild = properties.getIndexBuild();
        if (elasticsearch == null && indexBuild == null) {
            return IndexSettings.defaults();
        }
        IndexSettings defaults = IndexSettings.defaults();
        return new IndexSettings(
            elasticsearch == null || elasticsearch.getIndexName() == null
                ? defaults.keywordIndexName() : elasticsearch.getIndexName(),
            elasticsearch == null || elasticsearch.getNavigationIndexName() == null
                ? defaults.navigationIndexName() : elasticsearch.getNavigationIndexName(),
            elasticsearch == null || elasticsearch.getRouteIndexName() == null
                ? defaults.routeIndexName() : elasticsearch.getRouteIndexName(),
            indexBuild == null || indexBuild.getElasticsearchRefreshWait() == null
                ? defaults.refreshWait() : indexBuild.getElasticsearchRefreshWait()
        );
    }
}
