package org.smartledge.ai.rag.runtime.support;

import java.util.List;

/**
 * @description: 支撑组件
 * @author: Song
 **/

public final class DocumentKnowledgeMetadataKeys {

    public static final String SOURCE_TYPE = "sourceType";
    public static final String CHANNEL = "channel";
    public static final String SCORE = "score";
    public static final String DOCUMENT_ID = "documentId";
    public static final String DOCUMENT_NAME = "documentName";
    public static final String KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    public static final String KNOWLEDGE_BASE_NAME = "knowledgeBaseName";
    public static final String TASK_ID = "taskId";
    public static final String INDEX_TASK_ID = "indexTaskId";
    public static final String SOURCE_PARSE_TASK_ID = "sourceParseTaskId";
    public static final String PARENT_BLOCK_ID = "parentBlockId";
    public static final String PARENT_BLOCK_NO = "parentBlockNo";
    public static final String CHUNK_ID = "chunkId";
    public static final String CHUNK_NO = "chunkNo";
    public static final String SECTION_PATH = "sectionPath";
    public static final String STRUCTURE_NODE_ID = "structureNodeId";
    public static final String STRUCTURE_NODE_TYPE = "structureNodeType";
    public static final String CANONICAL_PATH = "canonicalPath";
    public static final String ITEM_INDEX = "itemIndex";
    public static final String CONTENT_WITH_WEIGHT = "contentWithWeight";
    public static final String CHUNK_TYPE = "chunkType";
    public static final String KEYWORDS = "keywords";
    public static final String QUESTIONS = "questions";
    public static final String PAGE_NO = "pageNo";
    public static final String PAGE_RANGE = "pageRange";
    public static final String BBOX_JSON = "bboxJson";
    public static final String SOURCE_BLOCK_IDS = "sourceBlockIds";
    public static final String TITLE = "title";
    public static final String URL = "url";
    public static final String TOOL_NAME = "toolName";
    public static final String ORIGINAL_SNIPPET = "originalSnippet";
    public static final String RRF_SCORE = "rrfScore";
    public static final String HYBRID_SCORE = "hybridScore";
    public static final String METADATA_BOOST = "metadataBoost";
    public static final String VECTOR_SCORE = "vectorScore";
    public static final String KEYWORD_SCORE = "keywordScore";
    public static final String RERANK_SCORE = "rerankScore";
    public static final String RERANK_RANK = "rerankRank";
    public static final String RERANK_MODEL = "rerankModel";
    public static final String RERANK_STATUS = "rerankStatus";
    public static final String RERANK_ERROR = "rerankError";
    public static final String RERANK_CANDIDATE_COUNT = "rerankCandidateCount";
    public static final String RERANK_TOP_K = "rerankTopK";
    public static final String CANDIDATE_ID = "candidateId";
    public static final String LINEAGE_IDENTITY = "lineageIdentity";
    public static final String IDENTITY_RESOLUTION_STATUS = "identityResolutionStatus";
    public static final String FINAL_SELECTION_REASON = "finalSelectionReason";
    /**
     * rank feature 观测串：把只作内部弱排序的 rank feature 分量（如 RAPTOR 词面 boost）
     * 以可读文本挂到候选上，供 O9 显式展示。它只做观测，不反向参与业务排序或过滤。
     */
    public static final String RANK_FEATURE = "rankFeature";
    public static final String SOURCE_STRUCTURE_ANCHOR = "sourceStructureAnchor";
    public static final String STRUCTURE_ANCHOR_MATCH_TYPE = "structureAnchorMatchType";
    public static final String STRUCTURE_ANCHOR_RAW_BODY = "structureAnchorRawBody";
    public static final String SOURCE_AUTHORED_HEADING = "sourceAuthoredHeading";
    public static final String STRUCTURE_NAVIGATION_REQUIRED_SOURCE = "structureNavigationRequiredSource";
    public static final String STRUCTURE_BODY_RESOLVED_FROM = "structureBodyResolvedFrom";
    public static final String STRUCTURE_BODY_CANDIDATE_KIND = "structureBodyCandidateKind";
    public static final String EVIDENCE_APPLICABILITY_STATUS = "evidenceApplicabilityStatus";
    public static final String EVIDENCE_APPLICABILITY_REASON = "evidenceApplicabilityReason";
    public static final String CONTEXT_IDENTITY = "contextIdentity";
    public static final String CITATION_IDENTITY = "citationIdentity";
    public static final String CITATION_EVIDENCE_TYPE = "citationEvidenceType";
    public static final String CONTEXT_ONLY = "contextOnly";
    public static final String SOURCE_EVIDENCE_RESOLVED = "sourceEvidenceResolved";
    public static final String EVIDENCE_SOURCE_CAPABILITY = "evidenceSourceCapability";
    public static final String EVIDENCE_GROUNDING_CONFIDENCE = "evidenceGroundingConfidence";
    public static final String EVIDENCE_QUOTE_COVERAGE = "evidenceQuoteCoverage";
    public static final String EVIDENCE_SOURCE_KIND = "evidenceSourceKind";
    public static final String EVIDENCE_QUALITY_SIGNALS = "evidenceQualitySignals";
    public static final String EVIDENCE_QUALITY_SIGNAL_SOURCE = "evidenceQualitySignalSource";
    public static final String CONTEXT_ARTIFACT = "contextArtifact";
    public static final String CONTEXT_DISPOSITION = "contextDisposition";
    public static final String RETRIEVAL_INTENT = "retrievalIntent";
    public static final String CHANNEL_WEIGHT = "channelWeight";
    public static final String TABLE_ID = "tableId";
    public static final String TABLE_NO = "tableNo";
    public static final String TABLE_TITLE = "tableTitle";
    public static final String TABLE_OPERATION = "tableOperation";
    public static final String TABLE_METRIC_COLUMN = "tableMetricColumn";
    public static final String TABLE_GROUP_BY_COLUMN = "tableGroupByColumn";
    public static final String TABLE_MATCHED_ROW_COUNT = "tableMatchedRowCount";
    public static final String TABLE_EVIDENCE_ROW_IDS = "tableEvidenceRowIds";
    public static final String TABLE_EVIDENCE_ROW_NOS = "tableEvidenceRowNos";
    public static final String TABLE_EVIDENCE_COLUMN_IDS = "tableEvidenceColumnIds";
    public static final String TABLE_EVIDENCE_COLUMN_NOS = "tableEvidenceColumnNos";
    public static final String TABLE_EVIDENCE_COLUMN_NAMES = "tableEvidenceColumnNames";
    public static final String TABLE_EVIDENCE_CELL_IDS = "tableEvidenceCellIds";
    public static final String TABLE_EVIDENCE_CELL_COORDINATES = "tableEvidenceCellCoordinates";
    public static final String TABLE_EVIDENCE_CELL_BBOX_JSONS = "tableEvidenceCellBboxJsons";
    public static final String KG_ENTITY_ID = "kgEntityId";
    public static final String KG_ENTITY_NAME = "kgEntityName";
    public static final String KG_CANONICAL_ENTITY_KEY = "kgCanonicalEntityKey";
    public static final String KG_CANONICAL_ENTITY_NAME = "kgCanonicalEntityName";
    public static final String KG_CANONICAL_ENTITY_COUNT = "kgCanonicalEntityCount";
    public static final String KG_CANONICAL_DOCUMENT_COUNT = "kgCanonicalDocumentCount";
    public static final String KG_RELATED_ENTITY_ID = "kgRelatedEntityId";
    public static final String KG_RELATED_ENTITY_NAME = "kgRelatedEntityName";
    public static final String KG_RELATION_ID = "kgRelationId";
    public static final String KG_RELATION_TYPE = "kgRelationType";
    public static final String KG_RELATION_GROUP_KEY = "kgRelationGroupKey";
    public static final String KG_RELATION_GROUP_RELATION_COUNT = "kgRelationGroupRelationCount";
    public static final String KG_RELATION_GROUP_EVIDENCE_COUNT = "kgRelationGroupEvidenceCount";
    public static final String KG_RELATION_GROUP_DOCUMENT_COUNT = "kgRelationGroupDocumentCount";
    public static final String KG_EVIDENCE_ID = "kgEvidenceId";
    public static final String KG_GRAPH_PATH = "kgGraphPath";
    public static final String KG_HOP_COUNT = "kgHopCount";
    public static final String KG_QUERY_PLAN_SOURCE = "kgQueryPlanSource";
    public static final String KG_QUERY_PLAN_ANSWER_TYPES = "kgQueryPlanAnswerTypes";
    public static final String KG_QUERY_PLAN_ENTITIES = "kgQueryPlanEntities";
    public static final String KG_NHOP_SEED_ENTITY_ID = "kgNhopSeedEntityId";
    public static final String KG_NHOP_SEED_ENTITY_NAME = "kgNhopSeedEntityName";
    public static final String KG_NHOP_PATH = "kgNhopPath";
    public static final String KG_COMMUNITY_ID = "kgCommunityId";
    public static final String KG_COMMUNITY_TITLE = "kgCommunityTitle";
    public static final String KG_COMMUNITY_SUMMARY = "kgCommunitySummary";
    public static final String KG_COMMUNITY_SUMMARY_ONLY = "kgCommunitySummaryOnly";
    public static final String KG_CROSS_DOCUMENT_COMMUNITY_KEY = "kgCrossDocumentCommunityKey";
    public static final String KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT = "kgCrossDocumentCommunityEntityCount";
    public static final String KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT = "kgCrossDocumentCommunityRelationGroupCount";
    public static final String KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT = "kgCrossDocumentCommunityEvidenceCount";
    public static final String KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT = "kgCrossDocumentCommunityDocumentCount";
    public static final String KG_COMMUNITY_RANK_SCORE = "kgCommunityRankScore";
    public static final String KG_COMMUNITY_RANK_REASONS = "kgCommunityRankReasons";
    public static final String KG_RANK_BOOST = "kgRankBoost";
    public static final String KG_QUALITY_SCORE = "kgQualityScore";
    public static final String KG_QUALITY_REASONS = "kgQualityReasons";
    public static final String KG_NOISE_REASONS = "kgNoiseReasons";
    public static final String KG_PAGERANK = "kgPagerank";
    public static final String KG_RANK_POSITION = "kgRankPosition";
    public static final String KG_DEGREE = "kgDegree";
    public static final List<String> GRAPH_RAG_METADATA_KEYS = List.of(
        KG_ENTITY_ID,
        KG_ENTITY_NAME,
        KG_CANONICAL_ENTITY_KEY,
        KG_CANONICAL_ENTITY_NAME,
        KG_CANONICAL_ENTITY_COUNT,
        KG_CANONICAL_DOCUMENT_COUNT,
        KG_RELATED_ENTITY_ID,
        KG_RELATED_ENTITY_NAME,
        KG_RELATION_ID,
        KG_RELATION_TYPE,
        KG_RELATION_GROUP_KEY,
        KG_RELATION_GROUP_RELATION_COUNT,
        KG_RELATION_GROUP_EVIDENCE_COUNT,
        KG_RELATION_GROUP_DOCUMENT_COUNT,
        KG_EVIDENCE_ID,
        KG_GRAPH_PATH,
        KG_HOP_COUNT,
        KG_QUERY_PLAN_SOURCE,
        KG_QUERY_PLAN_ANSWER_TYPES,
        KG_QUERY_PLAN_ENTITIES,
        KG_NHOP_SEED_ENTITY_ID,
        KG_NHOP_SEED_ENTITY_NAME,
        KG_NHOP_PATH,
        KG_COMMUNITY_ID,
        KG_COMMUNITY_TITLE,
        KG_COMMUNITY_SUMMARY,
        KG_COMMUNITY_SUMMARY_ONLY,
        KG_CROSS_DOCUMENT_COMMUNITY_KEY,
        KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT,
        KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT,
        KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT,
        KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT,
        KG_COMMUNITY_RANK_SCORE,
        KG_COMMUNITY_RANK_REASONS,
        KG_RANK_BOOST,
        KG_QUALITY_SCORE,
        KG_QUALITY_REASONS,
        KG_NOISE_REASONS,
        KG_PAGERANK,
        KG_RANK_POSITION,
        KG_DEGREE
    );
    public static final String RAPTOR_NODE_ID = "raptorNodeId";
    public static final String RAPTOR_NODE_TITLE = "raptorNodeTitle";
    public static final String RAPTOR_NODE_LEVEL = "raptorNodeLevel";
    public static final String RAPTOR_SUMMARY = "raptorSummary";
    public static final String RAPTOR_SOURCE_STATUS = "raptorSourceStatus";

    private DocumentKnowledgeMetadataKeys() {
    }
}
