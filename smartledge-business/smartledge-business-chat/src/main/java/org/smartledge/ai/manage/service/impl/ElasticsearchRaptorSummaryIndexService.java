package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentRaptorNode;
import org.smartledge.ai.knowledge.augmentation.model.es.RaptorSummaryIndexRecord;
import org.smartledge.ai.knowledge.augmentation.service.RaptorSummaryIndexService;
import org.smartledge.ai.knowledge.augmentation.support.RaptorScopeSupport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.manage.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchRaptorSummaryIndexService implements RaptorSummaryIndexService {

    private final ElasticsearchClient elasticsearchClient;
    private final DocumentManageProperties properties;
    private final ObjectMapper objectMapper;

    public ElasticsearchRaptorSummaryIndexService(
        @Qualifier("documentManageElasticsearchClient") ElasticsearchClient elasticsearchClient,
        DocumentManageProperties properties,
        ObjectMapper objectMapper) {
        this.elasticsearchClient = elasticsearchClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void indexNodes(List<SuperAgentRaptorNode> nodes) {
        if (CollUtil.isEmpty(nodes)) {
            return;
        }
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder()
            .index(properties.getElasticsearch().getRaptorSummaryIndexName());
        if (Boolean.TRUE.equals(properties.getIndexBuild().getElasticsearchRefreshWait())) {
            bulkBuilder.refresh(Refresh.WaitFor);
        }
        for (SuperAgentRaptorNode node : nodes) {
            if (node == null || node.getId() == null) {
                continue;
            }
            RaptorSummaryIndexRecord record = toIndexRecord(node);
            bulkBuilder.operations(operation -> operation
                .index(index -> index
                    .id(String.valueOf(record.getNodeId()))
                    .document(record)
                )
            );
        }
        try {
            long startedNanos = System.nanoTime();
            BulkResponse response = elasticsearchClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                String errorMessage = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(item -> item.id() + ":" + item.error().reason())
                    .collect(Collectors.joining("; "));
                throw new IllegalStateException("批量写入 RAPTOR 摘要索引失败: " + errorMessage);
            }
            log.info("RAPTOR 摘要节点已同步写入 Elasticsearch: nodeCount={}, index={}, refreshWait={}, costMillis={}",
                nodes.size(),
                properties.getElasticsearch().getRaptorSummaryIndexName(),
                Boolean.TRUE.equals(properties.getIndexBuild().getElasticsearchRefreshWait()),
                (System.nanoTime() - startedNanos) / 1_000_000L);
        }
        catch (IOException exception) {
            throw new IllegalStateException("写入 RAPTOR 摘要索引失败", exception);
        }
    }

    @Override
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        try {
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(properties.getElasticsearch().getRaptorSummaryIndexName())
                .refresh(true)
                .query(query -> query.bool(bool -> bool
                    .filter(filter -> filter.term(term -> term.field("documentId").value(documentId)))
                    .filter(filter -> filter.term(term -> term.field("taskId").value(taskId)))
                ))
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("删除 RAPTOR 摘要索引任务数据失败", exception);
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(properties.getElasticsearch().getRaptorSummaryIndexName())
                .refresh(true)
                .query(query -> query.term(term -> term
                    .field("documentId")
                    .value(documentId)
                ))
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("删除 RAPTOR 摘要索引文档数据失败", exception);
        }
    }

    @Override
    public void deleteByScope(String scopeType, String scopeKey) {
        if (StrUtil.isBlank(scopeType) || StrUtil.isBlank(scopeKey)) {
            return;
        }
        try {
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(properties.getElasticsearch().getRaptorSummaryIndexName())
                .refresh(true)
                .query(query -> query.bool(bool -> bool
                    .filter(filter -> filter.term(term -> term.field("scopeType").value(scopeType)))
                    .filter(filter -> filter.term(term -> term.field("scopeKey").value(scopeKey)))
                ))
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("删除 RAPTOR 摘要索引 scope 数据失败", exception);
        }
    }

    @Override
    public List<RaptorSummaryHit> search(String question,
                                         List<Long> documentIds,
                                         List<Long> taskIds,
                                         List<String> datasetScopeKeys,
                                         int topK) {
        if (StrUtil.isBlank(question) || CollUtil.isEmpty(documentIds) || CollUtil.isEmpty(taskIds) || topK <= 0) {
            return List.of();
        }
        List<FieldValue> documentValues = documentIds.stream().map(FieldValue::of).toList();
        List<FieldValue> taskValues = taskIds.stream().map(FieldValue::of).toList();
        List<FieldValue> scopeValues = CollUtil.emptyIfNull(datasetScopeKeys).stream().map(FieldValue::of).toList();
        String retrievalQuery = question.trim();
        try {
            SearchResponse<RaptorSummaryIndexRecord> response = elasticsearchClient.search(search -> search
                    .index(properties.getElasticsearch().getRaptorSummaryIndexName())
                    .size(Math.max(1, Math.min(topK, 50)))
                    .query(query -> query.bool(bool -> {
                        bool.filter(filter -> filter.bool(scopeFilter -> {
                            scopeFilter.should(should -> should.bool(documentScope -> documentScope
                                .filter(item -> item.terms(terms -> terms
                                    .field("documentId")
                                    .terms(values -> values.value(documentValues))
                                ))
                                .filter(item -> item.terms(terms -> terms
                                    .field("taskId")
                                    .terms(values -> values.value(taskValues))
                                ))
                            ));
                            if (CollUtil.isNotEmpty(scopeValues)) {
                                scopeFilter.should(should -> should.bool(datasetScope -> datasetScope
                                    .filter(item -> item.term(term -> term
                                        .field("scopeType")
                                        .value(RaptorScopeSupport.SCOPE_TYPE_DATASET)
                                    ))
                                    .filter(item -> item.terms(terms -> terms
                                        .field("scopeKey")
                                        .terms(values -> values.value(scopeValues))
                                    ))
                                    .filter(item -> item.terms(terms -> terms
                                        .field("sourceDocumentIds")
                                        .terms(values -> values.value(documentValues))
                                    ))
                                    .filter(item -> item.terms(terms -> terms
                                        .field("sourceTaskIds")
                                        .terms(values -> values.value(taskValues))
                                    ))
                                ));
                            }
                            scopeFilter.minimumShouldMatch("1");
                            return scopeFilter;
                        }));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("title")
                            .query(retrievalQuery)
                            .boost(9.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("summary")
                            .query(retrievalQuery)
                            .boost(7.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("questions")
                            .query(retrievalQuery)
                            .boost(6.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("sectionPath")
                            .query(retrievalQuery)
                            .boost(5.0f)
                        ));
                        bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                            .query(retrievalQuery)
                            .fields("title^9", "summaryWithWeight^7", "summary^6", "questions^5",
                                "keywords^4", "sectionPath^3")
                            .type(TextQueryType.BestFields)
                        ));
                        bool.minimumShouldMatch("1");
                        return bool;
                    })),
                RaptorSummaryIndexRecord.class);
            List<RaptorSummaryHit> hits = new ArrayList<>();
            for (Hit<RaptorSummaryIndexRecord> hit : response.hits().hits()) {
                RaptorSummaryIndexRecord source = hit.source();
                if (source == null || source.getNodeId() == null) {
                    continue;
                }
                hits.add(new RaptorSummaryHit(source.getNodeId(), hit.score() == null ? 0D : hit.score()));
            }
            return hits;
        }
        catch (IOException exception) {
            log.error("RAPTOR 摘要 BM25 检索失败, question={}", retrievalQuery, exception);
            return List.of();
        }
    }

    private RaptorSummaryIndexRecord toIndexRecord(SuperAgentRaptorNode node) {
        Map<String, Object> metadata = readMap(node.getMetadataJson());
        Map<String, Object> sourceMetadata = objectMap(metadata.get("sourceMetadata"));
        return RaptorSummaryIndexRecord.builder()
            .nodeId(node.getId())
            .documentId(node.getDocumentId())
            .taskId(node.getTaskId())
            .scopeType(safeText(node.getScopeType()))
            .scopeKey(safeText(node.getScopeKey()))
            .parentNodeId(node.getParentNodeId())
            .nodeLevel(node.getNodeLevel())
            .nodeNo(node.getNodeNo())
            .title(safeText(node.getTitle()))
            .summary(safeText(node.getSummary()))
            .summaryWithWeight(safeText(node.getSummaryWithWeight()))
            .sectionPath(safeText(node.getSectionPath()))
            .pageRange(safeText(node.getPageRange()))
            .keywords(readStringList(node.getKeywords()))
            .questions(readStringList(node.getQuestions()))
            .sourceChunkIds(readLongList(node.getSourceChunkIdsJson()))
            .sourceParentBlockIds(readLongList(node.getSourceParentBlockIdsJson()))
            .sourceDocumentIds(readLongList(node.getSourceDocumentIdsJson()))
            .sourceTaskIds(readLongList(node.getSourceTaskIdsJson()))
            .qualityScore(doubleValue(metadata.get("summaryQualityScore")))
            .summaryStrategy(safeText(sourceMetadata.get("summaryStrategy")))
            .build();
    }

    private Map<String, Object> readMap(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        }
        catch (Exception exception) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private List<String> readStringList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        }
        catch (Exception exception) {
            return List.of();
        }
    }

    private List<Long> readLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        }
        catch (Exception exception) {
            return List.of();
        }
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private String safeText(Object text) {
        return text == null ? "" : String.valueOf(text).trim();
    }
}
