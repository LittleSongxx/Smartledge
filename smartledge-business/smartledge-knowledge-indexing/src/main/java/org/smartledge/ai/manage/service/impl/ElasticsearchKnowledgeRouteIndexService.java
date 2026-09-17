package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.knowledge.indexing.port.DocumentSearchIndexConfigurationPort;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentProfile;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeScopeNode;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeTopicNode;
import org.smartledge.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeScopeNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.smartledge.ai.manage.model.es.KnowledgeRouteIndexRecord;
import org.smartledge.ai.manage.service.KnowledgeRouteIndexService;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentIndexStatusEnum;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@Slf4j
@AllArgsConstructor
@Service
@ConditionalOnProperty(prefix = "app.manage.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchKnowledgeRouteIndexService implements KnowledgeRouteIndexService {

    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(5);
    private static final AtomicLong LAST_REFRESH_TIME = new AtomicLong(0L);

    @Qualifier("documentManageElasticsearchClient")
    private final ElasticsearchClient elasticsearchClient;
    private final DocumentSearchIndexConfigurationPort searchIndexConfiguration;
    private final SuperAgentKnowledgeScopeNodeMapper scopeNodeMapper;
    private final SuperAgentKnowledgeTopicNodeMapper topicNodeMapper;
    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentDocumentProfileMapper documentProfileMapper;
    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;
    

    @Override
    public void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        long last = LAST_REFRESH_TIME.get();
        if (now - last < REFRESH_INTERVAL.toMillis()) {
            return;
        }
        if (!LAST_REFRESH_TIME.compareAndSet(last, now)) {
            return;
        }
        try {
            refreshAll();
        }
        catch (Exception exception) {
            LAST_REFRESH_TIME.set(0L);
            log.warn("刷新知识路由索引失败，将在下次查询时重试。", exception);
        }
    }

    @Override
    public RouteLexicalSearchResult search(String routingText, String entityType, int size, Collection<Long> knowledgeBaseIds) {
        if (StrUtil.isBlank(routingText) || StrUtil.isBlank(entityType)) {
            return RouteLexicalSearchResult.available(List.of());
        }
        refreshIfNeeded();
        List<String> entityTerms = extractEntityTerms(routingText);
        List<FieldValue> selectedKnowledgeBaseIds = knowledgeBaseIds == null
            ? List.of()
            : knowledgeBaseIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(FieldValue::of)
                .toList();
        try {
            SearchResponse<KnowledgeRouteIndexRecord> response = elasticsearchClient.search(search -> search
                    .index(routeIndexName())
                    .size(Math.max(1, Math.min(size, 10)))
                    .query(query -> query.bool(bool -> {
                        bool.filter(filter -> filter.term(term -> term.field("entityType").value(entityType)));
                        if (!selectedKnowledgeBaseIds.isEmpty()) {
                            bool.filter(filter -> filter.terms(terms -> terms
                                .field("knowledgeBaseId")
                                .terms(value -> value.value(selectedKnowledgeBaseIds))
                            ));
                        }
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("displayName")
                            .query(routingText)
                            .boost(12.0f)
                        ));
                        bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                            .query(routingText)
                            .fields("displayName^10", "aliasesText^8", "examplesText^6", "summaryText^5", "routeText^4", "descriptionText^3")
                            .type(TextQueryType.BestFields)
                        ));
                        for (String entityTerm : entityTerms) {
                            bool.should(should -> should.term(term -> term
                                .field("entityTerms")
                                .value(entityTerm)
                                .boost(9.0f)
                            ));
                        }
                        bool.minimumShouldMatch("1");
                        return bool;
                    })),
                KnowledgeRouteIndexRecord.class);
            List<RouteLexicalHit> hits = new ArrayList<>();
            for (Hit<KnowledgeRouteIndexRecord> hit : response.hits().hits()) {
                KnowledgeRouteIndexRecord source = hit.source();
                if (source == null) {
                    continue;
                }
                hits.add(new RouteLexicalHit(
                    source.getRouteId(),
                    source.getEntityId(),
                    source.getEntityType(),
                    source.getDocumentId(),
                    source.getKnowledgeBaseId(),
                    source.getScopeId(),
                    source.getTopicId(),
                    source.getDocumentName(),
                    hit.score() == null ? 0D : hit.score()
                ));
            }
            return RouteLexicalSearchResult.available(hits);
        }
        catch (IOException exception) {
            log.warn("知识路由 ES lexical 检索不可用: entityType={}, query='{}'", entityType, StrUtil.maxLength(routingText, 120), exception);
            return RouteLexicalSearchResult.unavailable("ROUTE_INDEX_IO_ERROR");
        }
    }

    @Override
    public void deleteDocumentRoute(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(routeIndexName())
                .refresh(true)
                .query(query -> query.bool(bool -> bool
                    .filter(filter -> filter.term(term -> term
                        .field("entityType")
                        .value("document")
                    ))
                    .filter(filter -> filter.term(term -> term
                        .field("documentId")
                        .value(documentId)
                    ))
                ))
            );
            log.info("知识路由索引中的文档路由快照已删除: documentId={}, index={}",
                documentId, routeIndexName());
        }
        catch (IOException exception) {
            throw new IllegalStateException("删除知识路由索引中的文档路由快照失败", exception);
        }
    }

    private void refreshAll() throws IOException {
        List<KnowledgeRouteIndexRecord> records = buildIndexRecords();
        String indexName = routeIndexName();
        elasticsearchClient.deleteByQuery(delete -> delete
            .index(indexName)
            .refresh(true)
            .query(query -> query.matchAll(matchAll -> matchAll))
        );
        if (records.isEmpty()) {
            log.info("知识路由索引刷新完成，但当前没有可写入的路由快照。");
            return;
        }
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder()
            .index(indexName)
            .refresh(Refresh.WaitFor);
        for (KnowledgeRouteIndexRecord record : records) {
            bulkBuilder.operations(operation -> operation.index(index -> index
                .id(record.getRouteId())
                .document(record)
            ));
        }
        BulkResponse response = elasticsearchClient.bulk(bulkBuilder.build());
        if (response.errors()) {
            String errorMessage = response.items().stream()
                .filter(item -> item.error() != null)
                .map(item -> item.id() + ":" + item.error().reason())
                .collect(Collectors.joining("; "));
            throw new IllegalStateException("批量写入知识路由索引失败: " + errorMessage);
        }
        log.info("知识路由索引刷新完成: recordCount={}, index={}", records.size(), indexName);
    }

    private List<KnowledgeRouteIndexRecord> buildIndexRecords() {
        List<KnowledgeRouteIndexRecord> records = new ArrayList<>();
        List<SuperAgentKnowledgeScopeNode> scopes = scopeNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode()));
        List<SuperAgentKnowledgeTopicNode> topics = topicNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode()));
        List<SuperAgentDocument> documents = documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId));
        Map<Long, SuperAgentDocumentProfile> profileMap = documentProfileMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
                .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
                .eq(SuperAgentDocumentProfile::getProfileStatus, 2))
            .stream()
            .collect(Collectors.toMap(SuperAgentDocumentProfile::getDocumentId, item -> item, (left, right) -> right));
        Map<String, List<SuperAgentKnowledgeTopicNode>> topicByScope = topics.stream()
            .collect(Collectors.groupingBy(topic -> routeKey(topic.getKnowledgeBaseId(), topic.getScopeId())));
        Map<Long, SuperAgentKnowledgeTopicNode> topicById = topics.stream()
            .filter(topic -> topic.getId() != null)
            .collect(Collectors.toMap(SuperAgentKnowledgeTopicNode::getId, topic -> topic, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<SuperAgentTopicDocumentRelation>> relationByTopic = topicDocumentRelationMapper.selectList(
                new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
                    .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.groupingBy(relation -> routeKey(relation.getKnowledgeBaseId(), relation.getTopicId())));

        for (SuperAgentKnowledgeScopeNode scope : scopes) {
            List<String> scopeTags = new ArrayList<>();
            topicByScope.getOrDefault(routeKey(scope.getKnowledgeBaseId(), scope.getId()), List.of()).forEach(topic -> {
                addUnique(scopeTags, topic.getTopicName());
                parseCommaText(topic.getAliases()).forEach(item -> addUnique(scopeTags, item));
            });
            records.add(KnowledgeRouteIndexRecord.builder()
                .routeId("scope:" + safeIdPart(scope.getKnowledgeBaseId()) + ":" + safeIdPart(scope.getId()))
                .entityType("scope")
                .entityId(scope.getId())
                .knowledgeBaseId(scope.getKnowledgeBaseId())
                .scopeId(scope.getId())
                .scopeName(scope.getScopeName())
                .displayName(safeText(scope.getScopeName()))
                .descriptionText(safeText(scope.getDescription()))
                .aliasesText(safeText(scope.getAliases()))
                .examplesText(safeText(scope.getExamples()))
                .summaryText(safeText(scope.getDescription()))
                .routeText(join(scope.getScopeName(), scope.getDescription(), scope.getAliases(), scope.getExamples()))
                .entityTerms(extractEntityTerms(join(scope.getScopeName(), scope.getAliases())))
                .tags(scopeTags)
                .build());
        }

        for (SuperAgentKnowledgeTopicNode topic : topics) {
            List<String> tags = new ArrayList<>();
            parseJsonArray(topic.getExamples()).forEach(item -> addUnique(tags, item));
            parseCommaText(topic.getAliases()).forEach(item -> addUnique(tags, item));
            records.add(KnowledgeRouteIndexRecord.builder()
                .routeId("topic:" + safeIdPart(topic.getKnowledgeBaseId()) + ":" + safeIdPart(topic.getId()))
                .entityType("topic")
                .entityId(topic.getId())
                .knowledgeBaseId(topic.getKnowledgeBaseId())
                .scopeId(topic.getScopeId())
                .topicId(topic.getId())
                .topicName(topic.getTopicName())
                .displayName(safeText(topic.getTopicName()))
                .descriptionText(safeText(topic.getDescription()))
                .aliasesText(safeText(topic.getAliases()))
                .examplesText(safeText(topic.getExamples()))
                .summaryText(join(topic.getAnswerShape(), topic.getExecutionPreference()))
                .routeText(join(
                    topic.getTopicName(),
                    topic.getDescription(),
                    topic.getAliases(),
                    topic.getExamples(),
                    topic.getAnswerShape(),
                    topic.getExecutionPreference()))
                .entityTerms(extractEntityTerms(join(topic.getTopicName(), topic.getAliases())))
                .tags(tags)
                .build());
        }

        for (SuperAgentDocument document : documents) {
            SuperAgentDocumentProfile profile = profileMap.get(document.getId());
            List<String> tags = new ArrayList<>();
            if (profile != null) {
                parseJsonArray(profile.getCoreTopics()).forEach(item -> addUnique(tags, item));
                parseJsonArray(profile.getExampleQuestions()).forEach(item -> addUnique(tags, item));
            }
            relationByTopic.forEach((topicKey, relations) -> relations.stream()
                .filter(relation -> document.getId().equals(relation.getDocumentId())
                    && Objects.equals(document.getKnowledgeBaseId(), relation.getKnowledgeBaseId()))
                .findFirst()
                .ifPresent(relation -> {
                    SuperAgentKnowledgeTopicNode topic = topicById.get(relation.getTopicId());
                    if (topic != null) {
                        addUnique(tags, topic.getTopicName());
                        parseCommaText(topic.getAliases()).forEach(item -> addUnique(tags, item));
                    }
                }));
            records.add(KnowledgeRouteIndexRecord.builder()
                .routeId("document:" + document.getId())
                .entityType("document")
                .entityId(document.getId())
                .documentId(document.getId())
                .knowledgeBaseId(document.getKnowledgeBaseId())
                .documentName(safeText(document.getDocumentName()))
                .displayName(safeText(document.getDocumentName()))
                .descriptionText(profile == null ? "" : safeText(profile.getDocumentType()))
                .aliasesText("")
                .examplesText(profile == null ? "" : joinJsonLike(parseJsonArray(profile.getExampleQuestions())))
                .summaryText(profile == null ? "" : safeText(profile.getDocumentSummary()))
                .routeText(join(
                    document.getDocumentName(),
                    profile == null ? "" : profile.getDocumentSummary(),
                    profile == null ? "" : profile.getCoreTopics(),
                    profile == null ? "" : profile.getExampleQuestions(),
                    profile == null ? "" : profile.getDocumentType()
                ))
                .entityTerms(extractEntityTerms(document.getDocumentName()))
                .tags(tags)
                .build());
        }
        return records;
    }

    private String routeIndexName() {
        DocumentSearchIndexConfigurationPort.IndexSettings settings = searchIndexConfiguration == null
            ? null
            : searchIndexConfiguration.currentIndexSettings();
        return settings == null || settings.routeIndexName() == null
            ? DocumentSearchIndexConfigurationPort.IndexSettings.defaults().routeIndexName()
            : settings.routeIndexName();
    }

    private String routeKey(Long knowledgeBaseId, Long id) {
        return safeIdPart(knowledgeBaseId) + ":" + safeIdPart(id);
    }

    private String safeIdPart(Long value) {
        return value == null ? "none" : String.valueOf(value);
    }

    private List<String> extractEntityTerms(String text) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String normalized = text.trim();
        for (String part : normalized.split("[\\s、，,；;：:（）()]+")) {
            String trimmed = part.trim();
            if (trimmed.length() < 2) {
                continue;
            }
            if (trimmed.matches(".*[A-Za-z].*") || trimmed.matches(".*\\d.*")) {
                terms.add(trimmed);
                terms.add(trimmed.toUpperCase(Locale.ROOT));
                terms.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(terms).stream().limit(20).toList();
    }

    private List<String> parseJsonArray(String raw) {
        String normalized = StrUtil.blankToDefault(raw, "").trim();
        if (normalized.isBlank() || "[]".equals(normalized)) {
            return List.of();
        }
        String body = normalized.replace("[", "").replace("]", "");
        if (body.isBlank()) {
            return List.of();
        }
        return List.of(body.split(",")).stream()
            .map(item -> item.replace("\"", "").trim())
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private List<String> parseCommaText(String raw) {
        String normalized = StrUtil.blankToDefault(raw, "").trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        return List.of(normalized.split(",")).stream()
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private String joinJsonLike(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(" ", values);
    }

    private String join(String... values) {
        return Arrays.stream(values)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" "));
    }

    private void addUnique(List<String> values, String value) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        if (!values.contains(value.trim())) {
            values.add(value.trim());
        }
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
