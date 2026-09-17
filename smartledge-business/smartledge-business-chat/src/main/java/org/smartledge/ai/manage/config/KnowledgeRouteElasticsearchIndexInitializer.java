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
public class KnowledgeRouteElasticsearchIndexInitializer {

    private final ElasticsearchClient elasticsearchClient;
    private final DocumentManageProperties properties;

    public KnowledgeRouteElasticsearchIndexInitializer(
        @Qualifier("documentManageElasticsearchClient") ElasticsearchClient elasticsearchClient,
        DocumentManageProperties properties) {
        this.elasticsearchClient = elasticsearchClient;
        this.properties = properties;
    }

    @PostConstruct
    public void initIndex() {
        DocumentManageProperties.Elasticsearch elasticsearch = properties.getElasticsearch();
        String indexName = elasticsearch.getRouteIndexName();
        String analyzer = elasticsearch.getAnalyzer();
        String searchAnalyzer = elasticsearch.getSearchAnalyzer();
        try {
            if (indexExists(indexName)) {
                log.info("Elasticsearch 知识路由索引 [{}] 已存在，跳过创建。", indexName);
                return;
            }
            createIndex(indexName, analyzer, searchAnalyzer);
            log.info("Elasticsearch 知识路由索引 [{}] 创建完成，analyzer={}, searchAnalyzer={}",
                indexName, analyzer, searchAnalyzer);
        }
        catch (IOException exception) {
            throw new IllegalStateException("初始化 Elasticsearch 知识路由索引失败: " + exception.getMessage(), exception);
        }
    }

    private boolean indexExists(String indexName) throws IOException {
        return elasticsearchClient.indices().exists(ExistsRequest.of(exists -> exists.index(indexName))).value();
    }

    private void createIndex(String indexName, String analyzer, String searchAnalyzer) throws IOException {
        elasticsearchClient.indices().create(create -> create
            .index(indexName)
            .mappings(mapping -> mapping
                .properties("routeId", property -> property.keyword(keyword -> keyword))
                .properties("entityType", property -> property.keyword(keyword -> keyword))
                .properties("entityId", property -> property.long_(number -> number))
                .properties("documentId", property -> property.long_(number -> number))
                .properties("knowledgeBaseId", property -> property.long_(number -> number))
                .properties("scopeId", property -> property.long_(number -> number))
                .properties("scopeName", property -> property.text(text -> text.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                .properties("topicId", property -> property.long_(number -> number))
                .properties("topicName", property -> property.text(text -> text.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                .properties("documentName", property -> property.text(text -> text.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                .properties("displayName", property -> property.text(text -> text.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                .properties("descriptionText", property -> property.text(text -> text.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                .properties("aliasesText", property -> property.text(text -> text.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                .properties("examplesText", property -> property.text(text -> text.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                .properties("summaryText", property -> property.text(text -> text.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                .properties("routeText", property -> property.text(text -> text.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                .properties("entityTerms", property -> property.keyword(keyword -> keyword))
                .properties("tags", property -> property.keyword(keyword -> keyword))
            )
        );
    }

}
