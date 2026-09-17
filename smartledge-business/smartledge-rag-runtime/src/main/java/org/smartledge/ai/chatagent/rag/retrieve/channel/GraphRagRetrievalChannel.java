package org.smartledge.ai.chatagent.rag.retrieve.channel;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.chatagent.rag.model.RetrievalExecutionRequest;
import org.smartledge.ai.chatagent.rag.support.EvidenceCandidateNormalizer;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchRequest;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchResponse;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchResult;
import org.smartledge.ai.rag.runtime.port.DocumentEvidencePort;
import org.smartledge.ai.rag.runtime.port.GraphRagSearchPort;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.enums.RetrievalChannelEnum;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GraphRagRetrievalChannel implements RetrievalChannel {

    private static final String SOURCE_TYPE = "GRAPH_RAG";

    private final GraphRagSearchPort graphRagSearchService;
    private final DocumentEvidencePort documentKnowledgeService;

    public GraphRagRetrievalChannel(GraphRagSearchPort graphRagSearchService,
                                    DocumentEvidencePort documentKnowledgeService) {
        this.graphRagSearchService = graphRagSearchService;
        this.documentKnowledgeService = documentKnowledgeService;
    }

    @Override
    public String channelName() {
        return RetrievalChannelEnum.GRAPH_RAG.getName();
    }

    @Override
    public RetrievalChannelResult retrieve(RetrievalExecutionRequest request) {
        RetrievalExecutionRequest.ChannelSpec channel = request.requireChannel(channelName());
        GraphRagSearchRequest graphRequest = new GraphRagSearchRequest(
            request.sourceQuestion(),
            request.executionQuery(),
            request.documentScope(),
            request.taskScope(),
            channel.topK(),
            request.graphIntent().maxHops(),
            request.filters().entityHints()
        );
        GraphRagSearchResponse response = graphRagSearchService.search(graphRequest);
        List<GraphRagSearchResult> results = response.results();
        Map<String, Object> observation = observation(response);
        if (results.isEmpty()) {
            return new RetrievalChannelResult(channelName(), List.of(), observation, "");
        }

        Map<Long, KnowledgeDocumentDescriptor> documentDescriptors = resolveDocumentDescriptors(request);
        List<RetrievalDocument> documents = results.stream()
            .map(result -> toDocument(result, documentDescriptors))
            .toList();
        return new RetrievalChannelResult(channelName(), documents, observation, "");
    }

    private Map<String, Object> observation(GraphRagSearchResponse response) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("entityHintsArrived", response.observation().entityHintsArrived());
        values.put("receivedEntityHints", response.observation().receivedEntityHints());
        values.put("usedEntitySeeds", response.observation().usedEntitySeeds());
        values.put("fallbackOccurred", response.observation().fallbackOccurred());
        values.put("fallbackReason", response.observation().fallbackReason().name());
        values.put("resultSources", response.observation().resultSources().stream().map(Enum::name).toList());
        return values;
    }

    private RetrievalDocument toDocument(GraphRagSearchResult result,
                                Map<Long, KnowledgeDocumentDescriptor> documentDescriptors) {
        KnowledgeDocumentDescriptor descriptor = documentDescriptors.get(result.getDocumentId());
        String documentName = StrUtil.blankToDefault(descriptor == null ? null : descriptor.getDocumentName(), "文档图谱");
        String text = hasSourceQuoteEvidence(result)
            ? result.getQuoteText().trim()
            : renderContextText(result);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, SOURCE_TYPE);
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channelName());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, result.getScore());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.DOCUMENT_ID, result.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, documentName);
        if (descriptor != null) {
            putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, descriptor.getKnowledgeBaseId());
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, StrUtil.blankToDefault(descriptor.getKnowledgeBaseName(), ""));
        }
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.TASK_ID, result.getTaskId());
        if (!isCommunityReportResult(result)) {
            putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, result.getParentBlockId());
        }
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.CHUNK_ID, result.getChunkId());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, StrUtil.blankToDefault(result.getSectionPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, result.getPageNo());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, StrUtil.blankToDefault(result.getPageRange(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, StrUtil.blankToDefault(result.getBboxJson(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "GRAPH_RAG");
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, StrUtil.blankToDefault(result.getGraphPath(), "GraphRAG 图谱证据"));
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, StrUtil.blankToDefault(result.getQuoteText(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, result.getEntityId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, StrUtil.blankToDefault(result.getEntityName(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY, StrUtil.blankToDefault(result.getCanonicalEntityKey(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME, StrUtil.blankToDefault(result.getCanonicalEntityName(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT, result.getCanonicalEntityCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT, result.getCanonicalDocumentCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_ID, result.getRelatedEntityId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME, StrUtil.blankToDefault(result.getRelatedEntityName(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATION_ID, result.getRelationId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, StrUtil.blankToDefault(result.getRelationType(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, StrUtil.blankToDefault(result.getRelationGroupKey(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_RELATION_COUNT, result.getRelationGroupRelationCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT, result.getRelationGroupEvidenceCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT, result.getRelationGroupDocumentCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, result.getEvidenceId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, StrUtil.blankToDefault(result.getGraphPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_HOP_COUNT, result.getHopCount());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE, StrUtil.blankToDefault(result.getQueryPlanSource(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES, StrUtil.blankToDefault(result.getQueryPlanAnswerTypes(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ENTITIES, StrUtil.blankToDefault(result.getQueryPlanEntities(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_NHOP_SEED_ENTITY_ID, result.getNHopSeedEntityId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_NHOP_SEED_ENTITY_NAME, StrUtil.blankToDefault(result.getNHopSeedEntityName(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH, StrUtil.blankToDefault(result.getNHopPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_COMMUNITY_ID, result.getCommunityId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE, StrUtil.blankToDefault(result.getCommunityTitle(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY, StrUtil.blankToDefault(result.getCommunitySummary(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY_ONLY, isCommunitySummaryOnly(result));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, StrUtil.blankToDefault(result.getCrossDocumentCommunityKey(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT, result.getCrossDocumentCommunityEntityCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT, result.getCrossDocumentCommunityRelationGroupCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT, result.getCrossDocumentCommunityEvidenceCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT, result.getCrossDocumentCommunityDocumentCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_COMMUNITY_RANK_SCORE, result.getKgCommunityRankScore());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_RANK_REASONS, StrUtil.blankToDefault(result.getKgCommunityRankReasons(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RANK_BOOST, result.getRankBoost());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, result.getKgQualityScore());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_REASONS, StrUtil.blankToDefault(result.getKgQualityReasons(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_NOISE_REASONS, StrUtil.blankToDefault(result.getKgNoiseReasons(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_PAGERANK, result.getKgPagerank());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RANK_POSITION, result.getKgRankPosition());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_DEGREE, result.getKgDegree());

        RetrievalDocument document = RetrievalDocument.builder()
            .id(documentId(result))
            .text(text)
            .metadata(metadata)
            .score(result.getScore())
            .build();
        EvidenceCandidateNormalizer.enrichIdentity(document);
        return document;
    }

    private String renderContextText(GraphRagSearchResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("[GraphRAG 背景上下文]\n");
        builder.append("图谱路径：").append(StrUtil.blankToDefault(result.getGraphPath(), result.getEntityName())).append('\n');
        if (StrUtil.isNotBlank(result.getCommunitySummary())) {
            builder.append("社区报告：").append(result.getCommunitySummary()).append('\n');
            if (isCommunitySummaryOnly(result)) {
                builder.append("社区报告边界：该候选缺少可回到原文 quote 的 KG evidence，只能作为背景线索，不能单独支撑具体事实结论。\n");
            }
        }
        if (StrUtil.isNotBlank(result.getNHopPath())) {
            builder.append("n-hop路径：").append(result.getNHopPath()).append('\n');
        }
        if (StrUtil.isNotBlank(result.getSectionPath())) {
            builder.append("章节：").append(result.getSectionPath()).append('\n');
        }
        if (result.getPageNo() != null) {
            builder.append("页码：").append(result.getPageNo()).append('\n');
        }
        if (result.getRelationGroupDocumentCount() != null && result.getRelationGroupDocumentCount() > 1) {
            builder.append("跨文档关系组：")
                .append(result.getRelationGroupDocumentCount()).append(" 份文档 / ")
                .append(result.getRelationGroupEvidenceCount() == null ? "-" : result.getRelationGroupEvidenceCount())
                .append(" 条证据支撑\n");
        }
        if (result.getCrossDocumentCommunityDocumentCount() != null && result.getCrossDocumentCommunityDocumentCount() > 1) {
            builder.append("跨文档社区：")
                .append(result.getCrossDocumentCommunityDocumentCount()).append(" 份文档 / ")
                .append(result.getCrossDocumentCommunityRelationGroupCount() == null ? "-" : result.getCrossDocumentCommunityRelationGroupCount())
                .append(" 个关系组 / ")
                .append(result.getCrossDocumentCommunityEvidenceCount() == null ? "-" : result.getCrossDocumentCommunityEvidenceCount())
                .append(" 条证据支撑\n");
        }
        if (StrUtil.isNotBlank(result.getQuoteText())) {
            builder.append("关联片段：").append(result.getQuoteText()).append('\n');
        }
        return builder.toString().trim();
    }

    private boolean isCommunitySummaryOnly(GraphRagSearchResult result) {
        return isCommunityReportResult(result) && !hasSourceQuoteEvidence(result);
    }

    private boolean hasSourceQuoteEvidence(GraphRagSearchResult result) {
        return result != null
            && result.getEvidenceId() != null
            && result.getChunkId() != null
            && StrUtil.isNotBlank(result.getQuoteText());
    }

    private Map<Long, KnowledgeDocumentDescriptor> resolveDocumentDescriptors(RetrievalExecutionRequest request) {
        Map<Long, KnowledgeDocumentDescriptor> documentDescriptors = new LinkedHashMap<>();
        List<Long> documentIds = request.documentScope();
        List<KnowledgeDocumentDescriptor> descriptors = documentKnowledgeService
            .listRetrievableDocumentsByKnowledgeBaseIds(request.knowledgeBaseIds());
        for (KnowledgeDocumentDescriptor descriptor : descriptors) {
            if (descriptor.getDocumentId() != null) {
                documentDescriptors.put(descriptor.getDocumentId(), descriptor);
            }
        }
        documentDescriptors.keySet().retainAll(documentIds);
        return documentDescriptors;
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String documentId(GraphRagSearchResult result) {
        if (isCommunityReportResult(result)) {
            if (StrUtil.isNotBlank(result.getCrossDocumentCommunityKey())) {
                return "graphrag-xcommunity-" + stableIdPart(result.getCrossDocumentCommunityKey()) + "-evidence-" + stableEvidenceIdPart(result);
            }
            if (result.getCommunityId() != null) {
                return "graphrag-community-" + result.getCommunityId() + "-evidence-" + stableEvidenceIdPart(result);
            }
        }
        if (result.getEvidenceId() != null) {
            return "graphrag-" + result.getEvidenceId();
        }
        if (result.getRelationId() != null) {
            return "graphrag-relation-" + result.getRelationId();
        }
        if (result.getEntityId() != null) {
            return "graphrag-entity-" + result.getEntityId();
        }
        return "graphrag-" + Integer.toHexString(System.identityHashCode(result));
    }

    private boolean isCommunityReportResult(GraphRagSearchResult result) {
        if (result == null || result.getRelationId() != null || result.getEntityId() != null) {
            return false;
        }
        return result.getCommunityId() != null
            || StrUtil.isNotBlank(result.getCrossDocumentCommunityKey())
            || StrUtil.isNotBlank(result.getCommunityTitle())
            || StrUtil.isNotBlank(result.getCommunitySummary());
    }

    private String stableIdPart(String value) {
        String normalized = StrUtil.blankToDefault(value, "")
            .replaceAll("[^A-Za-z0-9._-]+", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-+|-+$", "");
        if (StrUtil.isBlank(normalized)) {
            return Integer.toHexString(value.hashCode());
        }
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private String stableEvidenceIdPart(GraphRagSearchResult result) {
        if (result != null && result.getEvidenceId() != null) {
            return String.valueOf(result.getEvidenceId());
        }
        return "summary";
    }
}
