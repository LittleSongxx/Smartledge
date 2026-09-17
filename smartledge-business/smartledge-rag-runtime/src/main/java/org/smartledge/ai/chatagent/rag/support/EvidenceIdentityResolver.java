package org.smartledge.ai.chatagent.rag.support;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.rag.model.CitationEvidenceType;
import org.smartledge.ai.chatagent.rag.model.EvidenceIdentity;
import org.smartledge.ai.chatagent.rag.model.EvidenceKind;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EvidenceIdentityResolver {

    private static final List<String> BODY_CHUNK_TYPES = List.of("TEXT", "LIST", "TABLE", "BODY");

    private EvidenceIdentityResolver() {
    }

    public static EvidenceIdentity citationIdentity(RetrievalDocument document) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        return citationIdentityFromMetadata(document.getMetadata(), document.getText());
    }

    public static EvidenceIdentity contextIdentity(RetrievalDocument document) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Map<String, Object> metadata = document.getMetadata();
        Long documentId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID));
        Long parentBlockId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
        Long chunkId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID));
        Long kgEvidenceId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID));
        Long raptorNodeId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID));
        Long tableId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_ID));
        if (parentBlockId != null) {
            return EvidenceIdentity.context("PARENT:" + documentScope(documentId) + parentBlockId);
        }
        if (chunkId != null) {
            return EvidenceIdentity.context("CHUNK_CONTEXT:" + documentScope(documentId) + chunkId);
        }
        if (kgEvidenceId != null) {
            return EvidenceIdentity.context("KG_CONTEXT:" + kgEvidenceId);
        }
        if (raptorNodeId != null) {
            return EvidenceIdentity.context("RAPTOR_CONTEXT:" + raptorNodeId + ":" + safeText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS)));
        }
        if (tableId != null) {
            return EvidenceIdentity.context("TABLE_CONTEXT:" + tableId);
        }
        if (document.getId() != null) {
            return EvidenceIdentity.context("DOC_OBJECT:" + document.getId());
        }
        return null;
    }

    public static EvidenceIdentity citationIdentity(SearchReference reference) {
        if (reference == null) {
            return null;
        }
        if (isWebReference(reference) && StrUtil.isNotBlank(reference.getUrl())) {
            return EvidenceIdentity.citation("WEB:" + reference.getUrl().trim(), CitationEvidenceType.WEB);
        }
        if (isTableEvidence(reference)) {
            return EvidenceIdentity.citation("TABLE:" + reference.getTableId() + ":" + tableEvidenceKey(reference), CitationEvidenceType.TABLE_CELL_OR_ROW);
        }
        if (reference.getKgEvidenceId() != null && reference.getChunkId() != null && StrUtil.isNotBlank(reference.getQuoteText())) {
            return EvidenceIdentity.citation("KG_QUOTE:" + reference.getKgEvidenceId() + ":CHUNK:" + reference.getChunkId(), CitationEvidenceType.KG_QUOTE_SOURCE);
        }
        if (isGraphCommunitySummary(reference)) {
            String communityKey = firstNonBlank(reference.getKgCrossDocumentCommunityKey(),
                reference.getKgEvidenceId() == null ? "" : String.valueOf(reference.getKgEvidenceId()));
            if (StrUtil.isNotBlank(communityKey)) {
                return EvidenceIdentity.citation("SUMMARY:KG:" + communityKey, CitationEvidenceType.SUMMARY);
            }
        }
        if (isRaptorSourceChunk(reference)
            && reference.getDocumentId() != null
            && reference.getChunkId() != null
            && StrUtil.isNotBlank(reference.getQuoteText())) {
            return EvidenceIdentity.citation("CHUNK:" + documentScope(reference.getDocumentId()) + reference.getChunkId(), CitationEvidenceType.RAPTOR_SOURCE_CHUNK);
        }
        if (isRaptorParent(reference)) {
            return EvidenceIdentity.citation(
                "PARENT:" + documentScope(reference.getDocumentId()) + reference.getParentBlockId(),
                CitationEvidenceType.PARENT
            );
        }
        if (isRaptorSummary(reference)) {
            return EvidenceIdentity.citation("SUMMARY:RAPTOR:" + reference.getRaptorNodeId(), CitationEvidenceType.SUMMARY);
        }
        if (isParentEvidence(reference)) {
            return EvidenceIdentity.citation(
                "PARENT:" + documentScope(reference.getDocumentId()) + reference.getParentBlockId(),
                CitationEvidenceType.PARENT
            );
        }
        if (reference.getDocumentId() != null && reference.getChunkId() != null && isRawDocumentChunk(reference)) {
            return EvidenceIdentity.citation("CHUNK:" + documentScope(reference.getDocumentId()) + reference.getChunkId(), CitationEvidenceType.CHUNK);
        }
        if (StrUtil.isNotBlank(reference.getToolName()) && StrUtil.isNotBlank(reference.getCitationIdentity())) {
            return EvidenceIdentity.citation(reference.getCitationIdentity(), CitationEvidenceType.TOOL);
        }
        return null;
    }

    public static EvidenceIdentity contextIdentity(SearchReference reference) {
        if (reference == null) {
            return null;
        }
        if (reference.getParentBlockId() != null) {
            return EvidenceIdentity.context("PARENT:" + documentScope(reference.getDocumentId()) + reference.getParentBlockId());
        }
        if (reference.getChunkId() != null) {
            return EvidenceIdentity.context("CHUNK_CONTEXT:" + documentScope(reference.getDocumentId()) + reference.getChunkId());
        }
        if (reference.getKgEvidenceId() != null) {
            return EvidenceIdentity.context("KG_CONTEXT:" + reference.getKgEvidenceId());
        }
        if (reference.getRaptorNodeId() != null) {
            return EvidenceIdentity.context("RAPTOR_CONTEXT:" + reference.getRaptorNodeId() + ":" + StrUtil.blankToDefault(reference.getRaptorSourceStatus(), ""));
        }
        if (reference.getTableId() != null) {
            return EvidenceIdentity.context("TABLE_CONTEXT:" + reference.getTableId());
        }
        if (StrUtil.isNotBlank(reference.getUrl())) {
            return EvidenceIdentity.context("WEB:" + reference.getUrl());
        }
        return EvidenceIdentity.context(StrUtil.blankToDefault(reference.getSourceType(), "UNKNOWN")
            + ":" + StrUtil.blankToDefault(reference.getTitle(), "")
            + ":" + StrUtil.blankToDefault(reference.getSnippet(), ""));
    }

    public static boolean sameCitationEvidence(RetrievalDocument left, RetrievalDocument right) {
        EvidenceIdentity leftIdentity = citationIdentity(left);
        EvidenceIdentity rightIdentity = citationIdentity(right);
        return samePresentIdentity(leftIdentity, rightIdentity);
    }

    public static boolean sameContext(RetrievalDocument left, RetrievalDocument right) {
        EvidenceIdentity leftIdentity = contextIdentity(left);
        EvidenceIdentity rightIdentity = contextIdentity(right);
        return samePresentIdentity(leftIdentity, rightIdentity);
    }

    public static boolean isCitationCapable(RetrievalDocument document) {
        EvidenceIdentity identity = citationIdentity(document);
        return identity != null && identity.present() && identity.citationCapable();
    }

    public static boolean isContextOnly(RetrievalDocument document) {
        return !isCitationCapable(document);
    }

    public static boolean isContextOnly(SearchReference reference) {
        EvidenceIdentity identity = citationIdentity(reference);
        return identity == null || !identity.present() || !identity.citationCapable();
    }

    public static String citationIdentityValue(RetrievalDocument document) {
        EvidenceIdentity identity = citationIdentity(document);
        return identity == null ? "" : StrUtil.blankToDefault(identity.value(), "");
    }

    /**
     * Groups citation variants that are grounded in the same original chunk without weakening citation identity.
     * Table row/cell identities remain independent because each one may carry different structured facts.
     */
    public static String sourceFamilyIdentityValue(RetrievalDocument document) {
        String citationIdentity = citationIdentityValue(document);
        if (citationIdentity.isBlank() || document == null || document.getMetadata() == null) {
            return citationIdentity;
        }
        Map<String, Object> metadata = document.getMetadata();
        if (isTableEvidence(metadata)) {
            return citationIdentity;
        }
        Long chunkId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID));
        if (chunkId == null) {
            return citationIdentity;
        }
        Long documentId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID));
        return "CHUNK:" + documentScope(documentId) + chunkId;
    }

    public static String contextIdentityValue(RetrievalDocument document) {
        EvidenceIdentity identity = contextIdentity(document);
        return identity == null ? "" : StrUtil.blankToDefault(identity.value(), "");
    }

    public static CitationEvidenceType citationEvidenceType(RetrievalDocument document) {
        EvidenceIdentity identity = citationIdentity(document);
        return identity == null ? CitationEvidenceType.CONTEXT_ONLY : identity.type();
    }

    public static EvidenceKind evidenceKind(RetrievalDocument document) {
        return EvidenceKind.from(citationEvidenceType(document));
    }

    private static EvidenceIdentity citationIdentityFromMetadata(Map<String, Object> metadata, String text) {
        Long documentId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID));
        Long chunkId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID));
        Long parentBlockId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
        Long kgEvidenceId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID));
        Long tableId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_ID));
        Long raptorNodeId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID));
        String sourceType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        String url = safeText(metadata.get(DocumentKnowledgeMetadataKeys.URL));
        String toolName = safeText(metadata.get(DocumentKnowledgeMetadataKeys.TOOL_NAME));

        if ("WEB".equalsIgnoreCase(sourceType) && StrUtil.isNotBlank(url)) {
            return EvidenceIdentity.citation("WEB:" + url, CitationEvidenceType.WEB);
        }
        if (isTableEvidence(metadata)) {
            return EvidenceIdentity.citation("TABLE:" + tableId + ":" + tableEvidenceKey(metadata), CitationEvidenceType.TABLE_CELL_OR_ROW);
        }
        if (isGraphRagQuoteEvidence(metadata, text)) {
            String sourceChunk = chunkId == null ? "" : ":CHUNK:" + chunkId;
            return EvidenceIdentity.citation("KG_QUOTE:" + kgEvidenceId + sourceChunk, CitationEvidenceType.KG_QUOTE_SOURCE);
        }
        if (isGraphCommunitySummary(metadata)) {
            String communityKey = firstNonBlank(
                safeText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY)),
                safeText(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_ID)),
                kgEvidenceId == null ? "" : String.valueOf(kgEvidenceId)
            );
            if (StrUtil.isNotBlank(communityKey)) {
                return EvidenceIdentity.citation("SUMMARY:KG:" + communityKey, CitationEvidenceType.SUMMARY);
            }
        }
        if (isRaptorSourceChunk(metadata)
            && documentId != null
            && chunkId != null
            && StrUtil.isNotBlank(safeText(metadata.get(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET)))) {
            return EvidenceIdentity.citation("CHUNK:" + documentScope(documentId) + chunkId, CitationEvidenceType.RAPTOR_SOURCE_CHUNK);
        }
        if (isRaptorParent(metadata) && parentBlockId != null) {
            return EvidenceIdentity.citation("PARENT:" + documentScope(documentId) + parentBlockId, CitationEvidenceType.PARENT);
        }
        if (isRaptorSummary(metadata) && raptorNodeId != null) {
            return EvidenceIdentity.citation("SUMMARY:RAPTOR:" + raptorNodeId, CitationEvidenceType.SUMMARY);
        }
        if (isParentEvidence(metadata) && parentBlockId != null) {
            return EvidenceIdentity.citation("PARENT:" + documentScope(documentId) + parentBlockId, CitationEvidenceType.PARENT);
        }
        if (isRawDocumentChunk(metadata) && documentId != null && chunkId != null) {
            return EvidenceIdentity.citation("CHUNK:" + documentScope(documentId) + chunkId, CitationEvidenceType.CHUNK);
        }
        if (StrUtil.isNotBlank(toolName) && (chunkId != null || parentBlockId != null)) {
            if (chunkId != null && documentId != null) {
                return EvidenceIdentity.citation("CHUNK:" + documentScope(documentId) + chunkId, CitationEvidenceType.CHUNK);
            }
            if (parentBlockId != null) {
                return EvidenceIdentity.citation("PARENT:" + documentScope(documentId) + parentBlockId, CitationEvidenceType.PARENT);
            }
        }
        return null;
    }

    private static boolean samePresentIdentity(EvidenceIdentity left, EvidenceIdentity right) {
        return left != null && right != null
            && left.present()
            && right.present()
            && Objects.equals(left.value(), right.value());
    }

    private static boolean isRawDocumentChunk(Map<String, Object> metadata) {
        String sourceType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        if ("GRAPH_RAG".equalsIgnoreCase(sourceType) || "RAPTOR".equalsIgnoreCase(sourceType) || "DOCUMENT_TABLE".equalsIgnoreCase(sourceType)) {
            return false;
        }
        String chunkType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE)).toUpperCase();
        return BODY_CHUNK_TYPES.contains(chunkType)
            || "TITLE".equals(chunkType) && asBoolean(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_AUTHORED_HEADING))
            || chunkType.isBlank();
    }

    private static boolean isRawDocumentChunk(SearchReference reference) {
        String sourceType = StrUtil.blankToDefault(reference.getSourceType(), "");
        if ("GRAPH_RAG".equalsIgnoreCase(sourceType) || "RAPTOR".equalsIgnoreCase(sourceType) || "DOCUMENT_TABLE".equalsIgnoreCase(sourceType)) {
            return false;
        }
        String chunkType = StrUtil.blankToDefault(reference.getChunkType(), "").trim().toUpperCase();
        return BODY_CHUNK_TYPES.contains(chunkType)
            || "TITLE".equals(chunkType) && reference.isSourceAuthoredHeading()
            || chunkType.isBlank();
    }

    private static boolean isGraphRagQuoteEvidence(Map<String, Object> metadata, String text) {
        Long kgEvidenceId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID));
        Long chunkId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID));
        if (kgEvidenceId == null || chunkId == null) {
            return false;
        }
        String originalSnippet = safeText(metadata.get(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET));
        return StrUtil.isNotBlank(originalSnippet);
    }

    private static boolean isRaptorSourceChunk(Map<String, Object> metadata) {
        String sourceStatus = safeText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS));
        String chunkType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE));
        return "SOURCE_CHUNK".equalsIgnoreCase(sourceStatus) || "RAPTOR_SOURCE_CHUNK".equalsIgnoreCase(chunkType);
    }

    private static boolean isRaptorSourceChunk(SearchReference reference) {
        return "SOURCE_CHUNK".equalsIgnoreCase(StrUtil.blankToDefault(reference.getRaptorSourceStatus(), ""));
    }

    private static boolean isGraphCommunitySummary(Map<String, Object> metadata) {
        if (asBoolean(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY_ONLY))) {
            return true;
        }
        if (!"GRAPH_RAG".equalsIgnoreCase(safeText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE)))) {
            return false;
        }
        if (isGraphRagQuoteEvidence(metadata, safeText(metadata.get(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET)))) {
            return false;
        }
        return asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_ID)) != null
            || StrUtil.isNotBlank(safeText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY)))
            || asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID)) != null;
    }

    private static boolean isGraphCommunitySummary(SearchReference reference) {
        if (reference.isKgCommunitySummaryOnly()) {
            return true;
        }
        return "GRAPH_RAG".equalsIgnoreCase(StrUtil.blankToDefault(reference.getSourceType(), ""))
            && StrUtil.isBlank(reference.getQuoteText())
            && (reference.getKgEvidenceId() != null || StrUtil.isNotBlank(reference.getKgCrossDocumentCommunityKey()));
    }

    private static boolean isRaptorParent(Map<String, Object> metadata) {
        return "SOURCE_PARENT_BLOCK".equalsIgnoreCase(safeText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS)))
            && asLong(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID)) != null;
    }

    private static boolean isRaptorParent(SearchReference reference) {
        return "SOURCE_PARENT_BLOCK".equalsIgnoreCase(StrUtil.blankToDefault(reference.getRaptorSourceStatus(), ""))
            && reference.getParentBlockId() != null;
    }

    private static boolean isRaptorSummary(Map<String, Object> metadata) {
        return "SUMMARY_ONLY".equalsIgnoreCase(safeText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS)))
            && asLong(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID)) != null;
    }

    private static boolean isRaptorSummary(SearchReference reference) {
        return "SUMMARY_ONLY".equalsIgnoreCase(StrUtil.blankToDefault(reference.getRaptorSourceStatus(), ""))
            && reference.getRaptorNodeId() != null;
    }

    private static boolean isParentEvidence(Map<String, Object> metadata) {
        Long parentBlockId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
        if (parentBlockId == null || isRawDocumentChunk(metadata) || isTableEvidence(metadata)) {
            return false;
        }
        String chunkType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE)).toUpperCase();
        return asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID)) == null
            || chunkType.contains("PARENT")
            || "PARENT_BLOCK".equalsIgnoreCase(safeText(metadata.get(DocumentKnowledgeMetadataKeys.CONTEXT_ARTIFACT)));
    }

    private static boolean isParentEvidence(SearchReference reference) {
        if (reference.getParentBlockId() == null || isRawDocumentChunk(reference) || isTableEvidence(reference)) {
            return false;
        }
        String chunkType = StrUtil.blankToDefault(reference.getChunkType(), "").trim().toUpperCase();
        return reference.getChunkId() == null || chunkType.contains("PARENT");
    }

    private static boolean isWebReference(SearchReference reference) {
        return reference != null && "WEB".equalsIgnoreCase(StrUtil.blankToDefault(reference.getSourceType(), "").trim());
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean isTableEvidence(Map<String, Object> metadata) {
        Long tableId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_ID));
        return tableId != null
            && (!asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_IDS)).isEmpty()
            || !asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_IDS)).isEmpty());
    }

    private static boolean isTableEvidence(SearchReference reference) {
        return reference.getTableId() != null
            && ((reference.getTableEvidenceCellIds() != null && !reference.getTableEvidenceCellIds().isEmpty())
            || (reference.getTableEvidenceRowIds() != null && !reference.getTableEvidenceRowIds().isEmpty()));
    }

    private static String tableEvidenceKey(Map<String, Object> metadata) {
        List<Long> cellIds = asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_IDS));
        if (!cellIds.isEmpty()) {
            return "CELLS:" + cellIds;
        }
        List<Long> rowIds = asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_IDS));
        if (!rowIds.isEmpty()) {
            return "ROWS:" + rowIds;
        }
        return "TABLE";
    }

    private static String tableEvidenceKey(SearchReference reference) {
        if (reference.getTableEvidenceCellIds() != null && !reference.getTableEvidenceCellIds().isEmpty()) {
            return "CELLS:" + reference.getTableEvidenceCellIds();
        }
        if (reference.getTableEvidenceRowIds() != null && !reference.getTableEvidenceRowIds().isEmpty()) {
            return "ROWS:" + reference.getTableEvidenceRowIds();
        }
        return "TABLE";
    }

    private static List<Long> asLongList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        java.util.ArrayList<Long> values = new java.util.ArrayList<>();
        for (Object item : iterable) {
            Long parsed = asLong(item);
            if (parsed != null) {
                values.add(parsed);
            }
        }
        return values;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String documentScope(Long documentId) {
        return documentId == null ? "" : documentId + ":";
    }
}
