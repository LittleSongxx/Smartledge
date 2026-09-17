package org.smartledge.ai.knowledge.augmentation.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.knowledge.augmentation.port.RaptorSummaryIndexConfigurationPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.manage.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RaptorSummaryElasticsearchIndexInitializer {

    private final ElasticsearchClient elasticsearchClient;
    private final RaptorSummaryIndexConfigurationPort configurationPort;

    public RaptorSummaryElasticsearchIndexInitializer(
        @Qualifier("documentManageElasticsearchClient") ElasticsearchClient elasticsearchClient,
        RaptorSummaryIndexConfigurationPort configurationPort) {
        this.elasticsearchClient = elasticsearchClient;
        this.configurationPort = configurationPort;
    }

    @PostConstruct
    public void initIndex() {
        RaptorSummaryIndexConfigurationPort.Configuration configuration = configurationPort.current();
        if (configuration == null || !configuration.enabled()) {
            return;
        }
        String indexName = configuration.indexName();
        String analyzer = configuration.analyzer();
        String searchAnalyzer = configuration.searchAnalyzer();
        try {
            if (indexExists(indexName)) {
                putScopeMappings(indexName);
                log.info("Elasticsearch RAPTOR 摘要索引 [{}] 已存在，已确认 scope/source 映射。", indexName);
                return;
            }
            createIndex(indexName, analyzer, searchAnalyzer);
            log.info("Elasticsearch RAPTOR 摘要索引 [{}] 创建完成，analyzer={}, searchAnalyzer={}",
                indexName, analyzer, searchAnalyzer);
        }
        catch (IOException exception) {
            throw new IllegalStateException("初始化 Elasticsearch RAPTOR 摘要索引失败: " + exception.getMessage(), exception);
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
                .properties("taskId", property -> property.long_(number -> number))
                .properties("scopeType", property -> property.keyword(keyword -> keyword))
                .properties("scopeKey", property -> property.keyword(keyword -> keyword))
                .properties("parentNodeId", property -> property.long_(number -> number))
                .properties("nodeLevel", property -> property.integer(number -> number))
                .properties("nodeNo", property -> property.integer(number -> number))
                .properties("title", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("summary", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("summaryWithWeight", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("sectionPath", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("pageRange", property -> property.keyword(keyword -> keyword))
                .properties("keywords", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("questions", property -> property.text(text -> text
                    .analyzer(analyzer)
                    .searchAnalyzer(searchAnalyzer)))
                .properties("sourceChunkIds", property -> property.long_(number -> number))
                .properties("sourceParentBlockIds", property -> property.long_(number -> number))
                .properties("sourceDocumentIds", property -> property.long_(number -> number))
                .properties("sourceTaskIds", property -> property.long_(number -> number))
                .properties("qualityScore", property -> property.double_(number -> number))
                .properties("summaryStrategy", property -> property.keyword(keyword -> keyword))
            )
        );
    }

    private void putScopeMappings(String indexName) throws IOException {
        elasticsearchClient.indices().putMapping(mapping -> mapping
            .index(indexName)
            .properties("scopeType", property -> property.keyword(keyword -> keyword))
            .properties("scopeKey", property -> property.keyword(keyword -> keyword))
            .properties("sourceDocumentIds", property -> property.long_(number -> number))
            .properties("sourceTaskIds", property -> property.long_(number -> number))
        );
    }
}
