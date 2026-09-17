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
public class DocumentElasticsearchIndexInitializer {

    private final ElasticsearchClient elasticsearchClient;
    private final DocumentManageProperties properties;

    public DocumentElasticsearchIndexInitializer(
        @Qualifier("documentManageElasticsearchClient") ElasticsearchClient elasticsearchClient,
        DocumentManageProperties properties) {
        this.elasticsearchClient = elasticsearchClient;
        this.properties = properties;
    }

    @PostConstruct
    public void initIndex() {
        DocumentManageProperties.Elasticsearch elasticsearch = properties.getElasticsearch();
        String indexName = elasticsearch.getIndexName();
        String analyzer = elasticsearch.getAnalyzer();
        String searchAnalyzer = elasticsearch.getSearchAnalyzer();
        try {
            if (indexExists(indexName)) {
                log.info("Elasticsearch 索引 [{}] 已存在，跳过创建。", indexName);
                return;
            }
            createIndex(indexName, analyzer, searchAnalyzer);
            log.info("Elasticsearch 索引 [{}] 创建完成，analyzer={}, searchAnalyzer={}",
                indexName, analyzer, searchAnalyzer);
        }
        catch (IOException exception) {
            throw new IllegalStateException("初始化 Elasticsearch 文档索引失败: " + exception.getMessage(), exception);
        }
    }

    private boolean indexExists(String indexName) throws IOException {
        return elasticsearchClient.indices().exists(ExistsRequest.of(exists -> exists.index(indexName))).value();
    }

    private void createIndex(String indexName, String analyzer, String searchAnalyzer) throws IOException {
        elasticsearchClient.indices().create(create -> create
            .index(indexName)
            .mappings(mapping -> mapping
                .properties("chunkId", property -> property.keyword(keyword -> keyword))
                .properties("documentId", property -> property.long_(number -> number))
                .properties("taskId", property -> property.long_(number -> number))
                .properties("parentBlockId", property -> property.long_(number -> number))
                .properties("chunkNo", property -> property.integer(number -> number))
                .properties("documentName", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("knowledgeBaseId", property -> property.long_(number -> number))
                .properties("knowledgeBaseName", property -> property.keyword(keyword -> keyword))
                .properties("sectionPath", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("structureNodeId", property -> property.long_(number -> number))
                .properties("structureNodeType", property -> property.integer(number -> number))
                .properties("canonicalPath", property -> property.keyword(keyword -> keyword))
                .properties("itemIndex", property -> property.integer(number -> number))
                .properties("pageNo", property -> property.integer(number -> number))
                .properties("pageRange", property -> property.keyword(keyword -> keyword))
                .properties("bboxJson", property -> property.keyword(keyword -> keyword))
                .properties("sourceBlockIds", property -> property.keyword(keyword -> keyword))
                .properties("contentWithWeight", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("chunkType", property -> property.keyword(keyword -> keyword))
                .properties("title", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("keywords", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("questions", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("chunkText", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
            )
        );
    }
}
