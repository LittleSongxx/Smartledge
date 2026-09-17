package org.smartledge.ai.chatagent.rag.support;

import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.rag.model.EvidenceIdentity;
import org.smartledge.ai.chatagent.rag.model.EvidenceKind;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @description: Mapper层
 * @author: Song
 **/

public final class SearchReferenceMapper {

    private SearchReferenceMapper() {
    }

    public static SearchReference fromDocument(RetrievalDocument document,
                                               int subQuestionIndex,
                                               String subQuestion,
                                               int referenceNumber) {
        Map<String, Object> metadata = document.getMetadata();
        String sourceType = asText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE), "DOCUMENT");
        SearchReference reference = new SearchReference();
        reference.setReferenceId(String.valueOf(referenceNumber));
        reference.setSourceType(sourceType);
        reference.setSnippet(document.getText());
        reference.setSubQuestionIndex(subQuestionIndex);
        reference.setSubQuestion(subQuestion);
        reference.setChannel(asText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL), "vector"));
        reference.setScore(asDouble(metadata.get(DocumentKnowledgeMetadataKeys.SCORE)));
        reference.setFinalSelectionReason(asText(metadata.get(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON), ""));
        reference.setEvidenceApplicabilityStatus(asText(metadata.get(DocumentKnowledgeMetadataKeys.EVIDENCE_APPLICABILITY_STATUS), ""));
        reference.setEvidenceApplicabilityReason(asText(metadata.get(DocumentKnowledgeMetadataKeys.EVIDENCE_APPLICABILITY_REASON), ""));
        reference.setContextIdentity(asText(metadata.get(DocumentKnowledgeMetadataKeys.CONTEXT_IDENTITY), ""));
        reference.setCitationIdentity(asText(metadata.get(DocumentKnowledgeMetadataKeys.CITATION_IDENTITY), ""));
        reference.setCitationEvidenceType(asText(metadata.get(DocumentKnowledgeMetadataKeys.CITATION_EVIDENCE_TYPE), ""));
        reference.setContextOnly(asBoolean(metadata.get(DocumentKnowledgeMetadataKeys.CONTEXT_ONLY)));
        reference.setSourceEvidenceResolved(asBoolean(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_EVIDENCE_RESOLVED)));

        if ("WEB".equalsIgnoreCase(sourceType)) {
            reference.setTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.TITLE), "网页来源"));
            reference.setUrl(asText(metadata.get(DocumentKnowledgeMetadataKeys.URL), ""));
            reference.setToolName(asText(metadata.get(DocumentKnowledgeMetadataKeys.TOOL_NAME), "tavily_search"));
            return reference;
        }

        reference.setTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME), "文档片段"));
        reference.setDocumentId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID)));
        reference.setTaskId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.TASK_ID)));
        reference.setDocumentName(asText(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME), ""));
        reference.setKnowledgeBaseId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID)));
        reference.setKnowledgeBaseName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME), ""));
        reference.setParentBlockId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID)));
        reference.setParentBlockNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO)));
        reference.setChunkId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID)));
        reference.setChunkType(asText(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE), ""));
        reference.setSourceAuthoredHeading(asBoolean(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_AUTHORED_HEADING)));
        reference.setChunkNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_NO)));
        reference.setSectionPath(asText(metadata.get(DocumentKnowledgeMetadataKeys.SECTION_PATH), ""));
        reference.setStructureNodeId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID)));
        reference.setStructureNodeType(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE)));
        reference.setCanonicalPath(asText(metadata.get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH), ""));
        reference.setItemIndex(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.ITEM_INDEX)));
        reference.setPageNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.PAGE_NO)));
        reference.setPageRange(asText(metadata.get(DocumentKnowledgeMetadataKeys.PAGE_RANGE), ""));
        reference.setBboxJson(asText(metadata.get(DocumentKnowledgeMetadataKeys.BBOX_JSON), ""));
        reference.setSourceBlockIds(asText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS), ""));
        reference.setTableId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_ID)));
        reference.setTableNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_NO)));
        reference.setTableTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_TITLE), ""));
        reference.setTableOperation(asText(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_OPERATION), ""));
        reference.setTableMetricColumn(asText(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_METRIC_COLUMN), ""));
        reference.setTableGroupByColumn(asText(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_GROUP_BY_COLUMN), ""));
        reference.setTableMatchedRowCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_MATCHED_ROW_COUNT)));
        reference.setTableEvidenceRowIds(asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_IDS)));
        reference.setTableEvidenceRowNos(asIntegerList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_NOS)));
        reference.setTableEvidenceColumnIds(asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_COLUMN_IDS)));
        reference.setTableEvidenceColumnNos(asIntegerList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_COLUMN_NOS)));
        reference.setTableEvidenceColumnNames(asStringList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_COLUMN_NAMES)));
        reference.setTableEvidenceCellIds(asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_IDS)));
        reference.setTableEvidenceCellCoordinates(asStringList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_COORDINATES)));
        reference.setTableEvidenceCellBboxJsons(asStringList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_BBOX_JSONS)));
        reference.setKgEntityId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID)));
        reference.setKgEntityName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME), ""));
        reference.setKgCanonicalEntityKey(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY), ""));
        reference.setKgCanonicalEntityName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME), ""));
        reference.setKgCanonicalEntityCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT)));
        reference.setKgCanonicalDocumentCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT)));
        reference.setKgRelatedEntityId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_ID)));
        reference.setKgRelatedEntityName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME), ""));
        reference.setKgRelationId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID)));
        reference.setKgRelationType(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE), ""));
        reference.setKgRelationGroupKey(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY), ""));
        reference.setKgRelationGroupRelationCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_RELATION_COUNT)));
        reference.setKgRelationGroupEvidenceCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT)));
        reference.setKgRelationGroupDocumentCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT)));
        reference.setKgEvidenceId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID)));
        reference.setKgGraphPath(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH), ""));
        reference.setKgHopCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_HOP_COUNT)));
        reference.setKgQueryPlanSource(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE), ""));
        reference.setKgQueryPlanAnswerTypes(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES), ""));
        reference.setKgQueryPlanEntities(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ENTITIES), ""));
        reference.setKgNhopSeedEntityId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_NHOP_SEED_ENTITY_ID)));
        reference.setKgNhopSeedEntityName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_NHOP_SEED_ENTITY_NAME), ""));
        reference.setKgNhopPath(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH), ""));
        reference.setKgCrossDocumentCommunityKey(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY), ""));
        reference.setKgCommunitySummaryOnly(asBoolean(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY_ONLY)));
        reference.setKgCrossDocumentCommunityEntityCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT)));
        reference.setKgCrossDocumentCommunityRelationGroupCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT)));
        reference.setKgCrossDocumentCommunityEvidenceCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT)));
        reference.setKgCrossDocumentCommunityDocumentCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT)));
        reference.setKgCommunityRankScore(asDouble(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_RANK_SCORE)));
        reference.setKgCommunityRankReasons(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_RANK_REASONS), ""));
        reference.setKgQualityScore(asDouble(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE)));
        reference.setKgQualityReasons(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_REASONS), ""));
        reference.setKgNoiseReasons(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_NOISE_REASONS), ""));
        reference.setKgPagerank(asDouble(metadata.get(DocumentKnowledgeMetadataKeys.KG_PAGERANK)));
        reference.setKgRankPosition(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_RANK_POSITION)));
        reference.setKgDegree(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_DEGREE)));
        reference.setRaptorNodeId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID)));
        reference.setRaptorNodeTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_TITLE), ""));
        reference.setRaptorNodeLevel(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_LEVEL)));
        reference.setRaptorSummary(asText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_SUMMARY), ""));
        reference.setRaptorSourceStatus(asText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS), ""));
        reference.setQuoteText(asText(metadata.get(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET), ""));
        enrichEvidenceIdentity(reference);
        return reference;
    }

    private static void enrichEvidenceIdentity(SearchReference reference) {
        EvidenceIdentity citationIdentity = EvidenceIdentityResolver.citationIdentity(reference);
        EvidenceIdentity contextIdentity = EvidenceIdentityResolver.contextIdentity(reference);
        if (citationIdentity != null && citationIdentity.present()) {
            reference.setCitationIdentity(citationIdentity.value());
            reference.setCitationEvidenceType(citationIdentity.type().name());
            EvidenceKind kind = EvidenceKind.from(citationIdentity.type());
            reference.setEvidenceKind(kind == null ? "" : kind.name());
            reference.setSourceEvidenceResolved(true);
            reference.setContextOnly(false);
        }
        else {
            reference.setCitationEvidenceType("CONTEXT_ONLY");
            reference.setEvidenceKind("");
            reference.setSourceEvidenceResolved(false);
            reference.setContextOnly(true);
        }
        if (contextIdentity != null && contextIdentity.present()) {
            reference.setContextIdentity(contextIdentity.value());
        }
    }

    private static String asText(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static List<Long> asLongList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        for (Object item : iterable) {
            Long parsed = asLong(item);
            if (parsed != null) {
                values.add(parsed);
            }
        }
        return values;
    }

    private static List<Integer> asIntegerList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        for (Object item : iterable) {
            Integer parsed = asInteger(item);
            if (parsed != null) {
                values.add(parsed);
            }
        }
        return values;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }
}
