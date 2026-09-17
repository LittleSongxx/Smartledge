package org.smartledge.ai.manage.service.keyword;

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
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.model.DocumentRetrieveFilters;
import org.smartledge.ai.manage.model.DocumentRetrieveRequest;
import org.smartledge.ai.manage.model.es.DocumentKeywordIndexRecord;
import org.smartledge.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagTypedChunkMetadataSupport;
import org.smartledge.ai.knowledge.indexing.port.DocumentSearchIndexConfigurationPort;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @description: 服务层
 * @author: Song
 **/

@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.manage.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchDocumentKeywordSearchGateway implements DocumentKeywordSearchGateway {

    private final ElasticsearchClient elasticsearchClient;
    private final SuperAgentDocumentMapper documentMapper;
    private final DocumentSearchIndexConfigurationPort searchIndexConfiguration;
    private final GraphRagTypedChunkMetadataSupport graphRagTypedChunkMetadataSupport;

    public ElasticsearchDocumentKeywordSearchGateway(
        @Qualifier("documentManageElasticsearchClient") ElasticsearchClient elasticsearchClient,
        SuperAgentDocumentMapper documentMapper,
        DocumentSearchIndexConfigurationPort searchIndexConfiguration,
        GraphRagTypedChunkMetadataSupport graphRagTypedChunkMetadataSupport) {
        this.elasticsearchClient = elasticsearchClient;
        this.documentMapper = documentMapper;
        this.searchIndexConfiguration = searchIndexConfiguration;
        this.graphRagTypedChunkMetadataSupport = graphRagTypedChunkMetadataSupport;
    }

    @Override
    public void indexChunks(List<SuperAgentDocumentChunk> chunkList) {
        if (CollUtil.isEmpty(chunkList)) {
            return;
        }

        Map<Long, SuperAgentDocument> documentMap = loadDocumentMap(chunkList);
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder()
            .index(keywordIndexName());
        if (refreshWait()) {
            bulkBuilder.refresh(Refresh.WaitFor);
        }

        for (SuperAgentDocumentChunk chunk : chunkList) {
            SuperAgentDocument document = documentMap.get(chunk.getDocumentId());
            DocumentKeywordIndexRecord indexRecord = toIndexRecord(chunk, document);
            bulkBuilder.operations(operation -> operation
                .index(index -> index
                    .id(indexRecord.getChunkId())
                    .document(indexRecord)
                )
            );
        }

        try {
            long startedNanos = System.nanoTime();
            BulkResponse response = elasticsearchClient.bulk(bulkBuilder.build());
            log.info("文档关键词索引 bulk 写入完成，chunkCount={}, refreshWait={}, costMillis={}",
                chunkList.size(), refreshWait(),
                (System.nanoTime() - startedNanos) / 1_000_000L);
            if (response.errors()) {
                String errorMessage = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(item -> item.id() + ":" + item.error().reason())
                    .collect(Collectors.joining("; "));
                throw new IllegalStateException("批量写入 Elasticsearch 失败: " + errorMessage);
            }
            log.info("文档 chunk 已同步写入 Elasticsearch: chunkCount={}, index={}",
                chunkList.size(), keywordIndexName());
        }
        catch (IOException exception) {
            throw new IllegalStateException("写入 Elasticsearch 失败", exception);
        }
    }

    @Override
    public void deleteByChunkIds(Long documentId, Long taskId, List<Long> chunkIds) {
        if (documentId == null || taskId == null || CollUtil.isEmpty(chunkIds)) {
            return;
        }
        List<FieldValue> chunkIdValues = chunkIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .map(id -> FieldValue.of(String.valueOf(id)))
            .toList();
        if (chunkIdValues.isEmpty()) {
            return;
        }
        try {
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(keywordIndexName())
                .refresh(true)
                .query(query -> query.bool(bool -> bool
                    .filter(filter -> filter.term(term -> term.field("documentId").value(documentId)))
                    .filter(filter -> filter.term(term -> term.field("taskId").value(taskId)))
                    .filter(filter -> filter.terms(terms -> terms
                        .field("chunkId")
                        .terms(values -> values.value(chunkIdValues))
                    ))
                ))
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("删除 Elasticsearch 指定 chunk 文档失败", exception);
        }
    }

    @Override
    public List<RetrievalDocument> search(DocumentRetrieveRequest request) {
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        List<FieldValue> documentFieldValues = request.resolvedDocumentIds().stream()
            .map(FieldValue::of)
            .toList();
        List<FieldValue> taskFieldValues = request.resolvedTaskIds().stream()
            .map(FieldValue::of)
            .toList();

        String retrievalQuery = request.getRetrievalQuery().trim();
        DocumentRetrieveFilters filters = request.getFilters();
        List<String> queryContextHints = request.getQueryContextHints() == null ? List.of() : request.getQueryContextHints();

        Long tenantId = org.smartledge.ai.manage.support.IndexTenantGuard.searchableTenantId();
        if (tenantId == null) {
            return List.of();
        }

        try {
            SearchResponse<DocumentKeywordIndexRecord> response = elasticsearchClient.search(search -> search
                    .index(keywordIndexName())
                    .size(resolveTopK(request.getTopK()))
                    .query(query -> query.bool(bool -> {

                        bool.filter(filter -> filter.term(term -> term
                            .field("tenantId")
                            .value(tenantId)
                        ));
                        bool.filter(filter -> filter.terms(terms -> terms
                            .field("documentId")
                            .terms(values -> values.value(documentFieldValues))
                        ));
                        bool.filter(filter -> filter.terms(terms -> terms
                            .field("taskId")
                            .terms(values -> values.value(taskFieldValues))
                        ));
                        bool.filter(filter -> filter.term(term -> term.field("status").value(1)));
                        bool.filter(filter -> filter.bool(fresh -> fresh
                            .should(should -> should.bool(missing -> missing.mustNot(mustNot -> mustNot.exists(exists -> exists.field("expiresAt")))))
                            .should(should -> should.range(range -> range.date(date -> date.field("expiresAt").gt("now"))))
                            .minimumShouldMatch("1")
                        ));
                        if (filters != null && filters.getUserMetadataEquals() != null) {
                            filters.getUserMetadataEquals().forEach((field, value) -> {
                                if (field == null || value == null) {
                                    return;
                                }
                                String name = field.startsWith("user.") ? field.substring(5) : field;
                                bool.filter(filter -> filter.term(term -> term
                                    .field("userMetadata." + name)
                                    .value(String.valueOf(value))
                                ));
                            });
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
                            bool.filter(filter -> filter.bool(sectionBool -> {
                                for (String sectionHint : filters.getSectionPathHints()) {
                                    sectionBool.should(should -> should.wildcard(wildcard -> wildcard
                                        .field("sectionPath")
                                        .value("*" + sectionHint.toLowerCase(Locale.ROOT) + "*")
                                    ));
                                }
                                sectionBool.minimumShouldMatch("1");
                                return sectionBool;
                            }));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getCanonicalPathHints())) {
                            bool.filter(filter -> filter.bool(pathBool -> {
                                for (String pathHint : filters.getCanonicalPathHints()) {
                                    pathBool.should(should -> should.wildcard(wildcard -> wildcard
                                        .field("canonicalPath")
                                        .value(pathHint + "*")
                                    ));
                                }
                                pathBool.minimumShouldMatch("1");
                                return pathBool;
                            }));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getStructureNodeIdHints())) {
                            List<FieldValue> structureNodeValues = filters.getStructureNodeIdHints().stream()
                                .map(FieldValue::of)
                                .toList();
                            bool.filter(filter -> filter.terms(terms -> terms
                                .field("structureNodeId")
                                .terms(values -> values.value(structureNodeValues))
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getItemIndexHints())) {
                            List<FieldValue> itemIndexValues = filters.getItemIndexHints().stream()
                                .map(FieldValue::of)
                                .toList();
                            bool.filter(filter -> filter.terms(terms -> terms
                                .field("itemIndex")
                                .terms(values -> values.value(itemIndexValues))
                            ));
                        }

                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("title")
                            .query(retrievalQuery)
                            .boost(10.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("sectionPath")
                            .query(retrievalQuery)
                            .boost(8.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("contentWithWeight")
                            .query(retrievalQuery)
                            .boost(6.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("questions")
                            .query(retrievalQuery)
                            .boost(5.5f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("chunkText")
                            .query(retrievalQuery)
                            .boost(4.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("documentName")
                            .query(retrievalQuery)
                            .boost(4.0f)
                        ));
                        bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                            .query(retrievalQuery)
                            .fields("title^10", "sectionPath^8", "contentWithWeight^6", "questions^5",
                                "keywords^5", "documentName^4", "chunkText^2")
                            .type(TextQueryType.BestFields)
                        ));
                        if (filters != null && CollUtil.isNotEmpty(filters.getDocumentNameHints())) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getDocumentNameHints()))
                                .fields("documentName^6", "title^4", "sectionPath^2", "contentWithWeight", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getYearHints())) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getYearHints()))
                                .fields("title^3", "sectionPath^2", "contentWithWeight^2", "chunkText^2", "documentName")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getSectionPathHints()))
                                .fields("sectionPath^7", "title^5", "contentWithWeight^2", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (CollUtil.isNotEmpty(queryContextHints)) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", queryContextHints))
                                .fields("documentName^2", "title^3", "sectionPath^2",
                                    "keywords^3", "questions^3", "contentWithWeight^2", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        bool.minimumShouldMatch("1");
                        return bool;
                    })),
                DocumentKeywordIndexRecord.class);

            List<RetrievalDocument> result = new ArrayList<>();
            for (Hit<DocumentKeywordIndexRecord> hit : response.hits().hits()) {
                DocumentKeywordIndexRecord source = hit.source();
                if (source == null) {
                    continue;
                }
                result.add(toRetrievalDocument(source, hit.score()));
            }
            return result;
        }
        catch (IOException exception) {
            log.error("Elasticsearch 关键词检索失败, retrievalQuery={}", retrievalQuery, exception);
            return List.of();
        }
    }

    @Override
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        try {
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(keywordIndexName())
                .refresh(true)
                .query(query -> query.bool(bool -> bool
                    .filter(filter -> filter.term(term -> term
                        .field("documentId")
                        .value(documentId)
                    ))
                    .filter(filter -> filter.term(term -> term
                        .field("taskId")
                        .value(taskId)
                    ))
                ))
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("删除 Elasticsearch 任务文档失败", exception);
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(keywordIndexName())
                .refresh(true)
                .query(query -> query.term(term -> term
                    .field("documentId")
                    .value(documentId)
                ))
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("删除 Elasticsearch 文档失败", exception);
        }
    }

    @Override
    public void tombstoneByDocumentId(Long documentId) {
        deleteByDocumentId(documentId);
    }

    @Override
    public void tombstoneByTask(Long documentId, Long taskId) {
        deleteByTask(documentId, taskId);
    }

    @Override
    public void tombstoneStaleTasks(Long documentId, Long currentTaskId) {
        if (documentId == null || currentTaskId == null || currentTaskId <= 0) {
            return;
        }
        try {
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(keywordIndexName())
                .refresh(true)
                .query(query -> query.bool(bool -> bool
                    .filter(filter -> filter.term(term -> term.field("documentId").value(documentId)))
                    .mustNot(mustNot -> mustNot.term(term -> term.field("taskId").value(currentTaskId)))
                ))
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("回收 Elasticsearch 旧世代失败", exception);
        }
    }

    private Map<Long, SuperAgentDocument> loadDocumentMap(List<SuperAgentDocumentChunk> chunkList) {
        List<Long> documentIds = chunkList.stream()
            .map(SuperAgentDocumentChunk::getDocumentId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        List<SuperAgentDocument> documents = documentMapper.selectBatchIds(documentIds);
        Map<Long, SuperAgentDocument> documentMap = new LinkedHashMap<>();
        for (SuperAgentDocument document : documents) {
            documentMap.put(document.getId(), document);
        }
        return documentMap;
    }

    private DocumentKeywordIndexRecord toIndexRecord(SuperAgentDocumentChunk chunk, SuperAgentDocument document) {
        return DocumentKeywordIndexRecord.builder()
            .chunkId(String.valueOf(chunk.getId()))
            .tenantId(document == null ? org.smartledge.database.tenant.TenantContext.get() : document.getTenantId())
            .documentId(chunk.getDocumentId())
            .taskId(chunk.getTaskId())
            .parentBlockId(chunk.getParentBlockId())
            .chunkNo(chunk.getChunkNo())
            .documentName(document == null ? "" : safeText(document.getDocumentName()))
            .knowledgeBaseId(document == null ? null : document.getKnowledgeBaseId())
            .knowledgeBaseName(document == null ? "" : safeText(document.getKnowledgeBaseName()))
            .sectionPath(safeText(chunk.getSectionPath()))
            .structureNodeId(chunk.getStructureNodeId())
            .structureNodeType(chunk.getStructureNodeType())
            .canonicalPath(safeText(chunk.getCanonicalPath()))
            .itemIndex(chunk.getItemIndex())
            .pageNo(chunk.getPageNo())
            .pageRange(safeText(chunk.getPageRange()))
            .bboxJson(safeText(chunk.getBboxJson()))
            .sourceBlockIds(safeText(chunk.getSourceBlockIds()))
            .contentWithWeight(safeText(chunk.getContentWithWeight()))
            .chunkType(safeText(chunk.getChunkType()))
            .title(safeText(chunk.getTitle()))
            .keywords(readStringArray(chunk.getKeywords()))
            .questions(readStringArray(chunk.getQuestions()))
            .chunkText(safeText(chunk.getChunkText()))
            .status(document == null || document.getStatus() == null ? 1 : document.getStatus())
            .expiresAt(document == null || document.getExpiresAt() == null ? null : document.getExpiresAt().toString())
            .userMetadata(flattenUserMetadata(document))
            .build();
    }

    private RetrievalDocument toRetrievalDocument(DocumentKeywordIndexRecord source, Double score) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        boolean graphTypedChunk = graphRagTypedChunkMetadataSupport.isGraphTypedChunk(source.getChunkType());
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, graphTypedChunk ? "GRAPH_RAG" : "DOCUMENT");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, "keyword");
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score == null ? 0D : score.doubleValue());
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, parseLong(source.getChunkId()));
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, source.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, source.getTaskId());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, source.getParentBlockId());
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, source.getChunkNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(source.getSectionPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, source.getStructureNodeId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE, source.getStructureNodeType());
        metadata.put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, safeText(source.getCanonicalPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.ITEM_INDEX, source.getItemIndex());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, source.getPageNo());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, safeText(source.getPageRange()));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, safeText(source.getBboxJson()));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, safeText(source.getSourceBlockIds()));
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, safeText(source.getDocumentName()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, source.getKnowledgeBaseId());
        metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, safeText(source.getKnowledgeBaseName()));
        metadata.put(DocumentKnowledgeMetadataKeys.CONTENT_WITH_WEIGHT, safeText(source.getContentWithWeight()));
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, safeText(source.getChunkType()));
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, safeText(source.getTitle()));
        metadata.put(DocumentKnowledgeMetadataKeys.KEYWORDS, String.join(",", source.getKeywords()));
        metadata.put(DocumentKnowledgeMetadataKeys.QUESTIONS, String.join(",", source.getQuestions()));
        if (!graphTypedChunk) {
            metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, safeText(source.getChunkText()));
        }
        graphRagTypedChunkMetadataSupport.enrichMetadata(metadata, source.getChunkType(), source.getSourceBlockIds());

        return RetrievalDocument.builder()
            .id(source.getChunkId())
            .text(source.getChunkText())
            .metadata(metadata)
            .score(score == null ? 0D : score.doubleValue())
            .build();
    }

    private boolean isSearchableRequest(DocumentRetrieveRequest request) {
        return request != null
            && StrUtil.isNotBlank(request.getQuestion())
            && StrUtil.isNotBlank(request.getRetrievalQuery())
            && !request.resolvedDocumentIds().isEmpty()
            && !request.resolvedTaskIds().isEmpty();
    }

    private String keywordIndexName() {
        DocumentSearchIndexConfigurationPort.IndexSettings settings = searchIndexConfiguration == null
            ? null
            : searchIndexConfiguration.currentIndexSettings();
        return settings == null || settings.keywordIndexName() == null
            ? DocumentSearchIndexConfigurationPort.IndexSettings.defaults().keywordIndexName()
            : settings.keywordIndexName();
    }

    private boolean refreshWait() {
        DocumentSearchIndexConfigurationPort.IndexSettings settings = searchIndexConfiguration == null
            ? null
            : searchIndexConfiguration.currentIndexSettings();
        return settings == null || !Boolean.FALSE.equals(settings.refreshWait());
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private int resolveTopK(int topK) {
        return topK <= 0 ? 10 : Math.min(topK, 50);
    }

    private List<String> readStringArray(String text) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        String normalized = text.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return Arrays.stream(normalized.split(","))
            .map(item -> item.replace("\"", "").trim())
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
    }

    private Long parseLong(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private java.util.Map<String, String> flattenUserMetadata(SuperAgentDocument document) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        if (document == null || StrUtil.isBlank(document.getMetadataJson())) {
            return values;
        }
        org.smartledge.ai.manage.support.IndexRetrievalFilter.flattenUserScalars(
            new org.smartledge.ai.manage.support.DocumentMetadataJsonParser().parse(document.getMetadataJson())
        ).forEach((key, value) -> {
            String name = key.startsWith("user.") ? key.substring(5) : key;
            values.put(name, value == null ? "" : String.valueOf(value));
        });
        return values;
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }
}
