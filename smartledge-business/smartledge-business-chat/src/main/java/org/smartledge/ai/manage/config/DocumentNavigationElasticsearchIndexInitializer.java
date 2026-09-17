package org.smartledge.ai.manage.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @description: 配置类
 * @author: Song
 **/

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.manage.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentNavigationElasticsearchIndexInitializer {

    private final ElasticsearchClient elasticsearchClient;
    private final DocumentManageProperties properties;

    public DocumentNavigationElasticsearchIndexInitializer(
        @Qualifier("documentManageElasticsearchClient") ElasticsearchClient elasticsearchClient,
        DocumentManageProperties properties) {
        this.elasticsearchClient = elasticsearchClient;
        this.properties = properties;
    }

    @PostConstruct
    public void initIndex() {
        DocumentManageProperties.Elasticsearch elasticsearch = properties.getElasticsearch();
        String indexName = elasticsearch.getNavigationIndexName();
        String analyzer = elasticsearch.getAnalyzer();
        String searchAnalyzer = elasticsearch.getSearchAnalyzer();
        try {
            if (indexExists(indexName)) {
                log.info("Elasticsearch 导航索引 [{}] 已存在，跳过创建。", indexName);
                return;
            }
            createIndex(indexName, analyzer, searchAnalyzer);
            log.info("Elasticsearch 导航索引 [{}] 创建完成，analyzer={}, searchAnalyzer={}",
                indexName, analyzer, searchAnalyzer);
        }
        catch (IOException exception) {
            throw new IllegalStateException("初始化 Elasticsearch 导航索引失败: " + exception.getMessage(), exception);
        }
    }

    private boolean indexExists(String indexName) throws IOException {
        return elasticsearchClient.indices().exists(ExistsRequest.of(exists -> exists.index(indexName))).value();
    }

    private void createIndex(String indexName, String analyzer, String searchAnalyzer) throws IOException {
        elasticsearchClient.indices().create(create -> create
            .index(indexName)
            .mappings(mapping -> mapping
                .properties("nodeId", property -> property.long_(number -> number))
                .properties("documentId", property -> property.long_(number -> number))
                .properties("parseTaskId", property -> property.long_(number -> number))
                .properties("nodeType", property -> property.keyword(keyword -> keyword))
                .properties("nodeCode", property -> property.keyword(keyword -> keyword))
                .properties("nodeNo", property -> property.integer(number -> number))
                .properties("depth", property -> property.integer(number -> number))
                .properties("parentNodeId", property -> property.long_(number -> number))
                .properties("title", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("anchorText", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("sectionPath", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("canonicalPath", property -> property.keyword(keyword -> keyword))
                .properties("contentText", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("itemIndex", property -> property.integer(number -> number))
            )
        );
    }

}
