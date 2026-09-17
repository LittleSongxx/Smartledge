package org.smartledge.ai.chatagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.chatagent.agent.AgentTool;
import org.smartledge.ai.chatagent.agent.AgentToolContext;
import org.smartledge.ai.chatagent.agent.AgentToolCallStatus;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.rag.model.EvidenceKind;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.smartledge.ai.chatagent.rag.service.HybridFusionService;
import org.smartledge.ai.chatagent.rag.service.RankFeatureService;
import org.smartledge.ai.chatagent.rag.support.EvidenceCandidateNormalizer;
import org.smartledge.ai.chatagent.rag.support.EvidenceIdentityResolver;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.rag.runtime.model.ChatToolDefinition;
import org.smartledge.ai.rag.runtime.model.DocumentRetrieveRequest;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.smartledge.ai.rag.runtime.port.DocumentEvidencePort;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.smartledge.enums.RetrievalChannelEnum;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent-facing knowledge search. It consumes the already-authorized RetrievalPlan and snapshot
 * scope; it never builds a second plan or re-interprets ACL.
 */
@Component
public class KnowledgeSearchTool implements AgentTool {

    public static final String NAME = "knowledge_search";

    private final DocumentEvidencePort documents;
    private final ChatRagProperties properties;
    private final ObjectMapper mapper;
    private final HybridFusionService fusion = new HybridFusionService(new RankFeatureService());

    public KnowledgeSearchTool(DocumentEvidencePort documents, ChatRagProperties properties, ObjectMapper mapper) {
        this.documents = documents;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public ChatToolDefinition definition() {
        return new ChatToolDefinition(
            NAME,
            "在当前会话已经授权的知识范围内检索文档片段。必须传非空 query。没有知识范围时不要调用。命中若已渲染为编号来源，回答必须用 ASCII [n] 引用。",
            Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")
            )
        );
    }

    @Override
    public Duration timeout() {
        return Duration.ofMillis(Math.max(1000L, properties.getAgentKnowledge().getTimeoutMs()));
    }

    @Override
    public String execute(String rawArguments, AgentToolContext context) throws Exception {
        context.checkActive();
        String query = readQuery(rawArguments);
        if (query.isBlank()) {
            return envelope(AgentToolCallStatus.REJECTED.name(), "EMPTY_QUERY", List.of());
        }
        KnowledgeBaseSelectionSnapshot snapshot = context.knowledgeScope();
        List<Long> documentIds = snapshot == null ? List.of() : copyPositive(snapshot.getAllowedDocumentIds());
        List<Long> taskIds = snapshot == null ? List.of() : copyPositive(snapshot.getAllowedTaskIds());
        KnowledgeBaseSelectionMode mode = snapshot == null || snapshot.getSelectionMode() == null
            ? KnowledgeBaseSelectionMode.NONE
            : snapshot.getSelectionMode();
        if (mode == KnowledgeBaseSelectionMode.NONE || documentIds.isEmpty() || taskIds.isEmpty()) {
            return envelope(AgentToolCallStatus.REJECTED.name(), "NO_SCOPE", List.of());
        }
        RetrievalPlan authorizedPlan = context.authorizedRetrievalPlan();
        if (authorizedPlan == null) {
            return envelope(AgentToolCallStatus.REJECTED.name(), "NO_PLAN", List.of());
        }

        int topK = Math.max(1, Math.min(properties.getAgentKnowledge().getTopK(), Math.max(1, authorizedPlan.getCandidateWindow())));
        DocumentRetrieveRequest request = new DocumentRetrieveRequest();
        request.setQuestion(query);
        request.setRetrievalQuery(query);
        request.setDocumentIds(documentIds);
        request.setTaskIds(taskIds);
        request.setTopK(topK);

        List<RetrievalDocument> vectorHits = documents.vectorSearch(request);
        List<RetrievalDocument> keywordHits = documents.keywordSearch(request);
        List<RetrievalDocument> fused = fusion.fuse(List.of(
            new RetrievalChannelResult(RetrievalChannelEnum.VECTOR.getName(), vectorHits == null ? List.of() : vectorHits),
            new RetrievalChannelResult(RetrievalChannelEnum.KEYWORD.getName(), keywordHits == null ? List.of() : keywordHits)
        ), authorizedPlan);

        List<Map<String, Object>> hits = new ArrayList<>();
        List<SearchReference> references = new ArrayList<>();
        for (RetrievalDocument document : fused) {
            markToolHit(document);
            EvidenceCandidateNormalizer.enrichIdentity(document);
            hits.add(hitView(document));
            SearchReference reference = toSourceReference(document);
            if (reference != null && EvidenceIdentityResolver.isCitationCapable(document)) {
                references.add(reference);
            }
        }
        context.addReferences(references);
        context.markToolUsed(NAME);
        return envelope(AgentToolCallStatus.SUCCEEDED.name(), "SOURCE", hits);
    }

    private String readQuery(String rawArguments) throws Exception {
        if (rawArguments == null || rawArguments.isBlank()) {
            return "";
        }
        JsonNode root = mapper.readTree(rawArguments);
        if (root == null || !root.hasNonNull("query")) {
            return rawArguments.trim();
        }
        return root.get("query").asText("").trim();
    }

    private void markToolHit(RetrievalDocument document) {
        Map<String, Object> metadata = document.getMetadata();
        metadata.put(DocumentKnowledgeMetadataKeys.TOOL_NAME, NAME);
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, "knowledge-search");
        metadata.remove(DocumentKnowledgeMetadataKeys.CONTEXT_ARTIFACT);
        metadata.remove(DocumentKnowledgeMetadataKeys.CONTEXT_ONLY);
        metadata.remove(DocumentKnowledgeMetadataKeys.CONTEXT_DISPOSITION);
    }

    private Map<String, Object> hitView(RetrievalDocument document) {
        Map<String, Object> hit = new LinkedHashMap<>();
        Map<String, Object> metadata = document.getMetadata();
        hit.put("title", text(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME)));
        if (hit.get("title").toString().isBlank()) {
            hit.put("title", text(metadata.get(DocumentKnowledgeMetadataKeys.TITLE)));
        }
        hit.put("snippet", clip(document.getText(), 280));
        hit.put("documentId", text(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID)));
        hit.put("taskId", text(metadata.get(DocumentKnowledgeMetadataKeys.TASK_ID)));
        boolean citationCapable = EvidenceIdentityResolver.isCitationCapable(document);
        EvidenceKind kind = EvidenceIdentityResolver.evidenceKind(document);
        hit.put("citationEligible", citationCapable);
        hit.put("evidenceKind", kind == null ? "" : kind.name());
        hit.put("pool", citationCapable ? "SOURCE" : "CONTEXT_ONLY");
        return hit;
    }

    private SearchReference toSourceReference(RetrievalDocument document) {
        Map<String, Object> metadata = document.getMetadata();
        SearchReference reference = new SearchReference();
        reference.setSourceType("KNOWLEDGE");
        reference.setTitle(text(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME)));
        reference.setSnippet(clip(document.getText(), 280));
        reference.setDocumentId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID)));
        reference.setTaskId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.TASK_ID)));
        reference.setChunkId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID)));
        reference.setChunkType(text(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE)));
        reference.setToolName(NAME);
        reference.setChannel("knowledge-search");
        reference.setCitationIdentity(EvidenceIdentityResolver.citationIdentityValue(document));
        EvidenceKind kind = EvidenceIdentityResolver.evidenceKind(document);
        reference.setEvidenceKind(kind == null ? "" : kind.name());
        reference.setCitationEvidenceType(EvidenceIdentityResolver.citationEvidenceType(document).name());
        reference.setContextOnly(!EvidenceIdentityResolver.isCitationCapable(document));
        reference.setSourceEvidenceResolved(EvidenceIdentityResolver.isCitationCapable(document));
        return reference;
    }

    private String envelope(String status, String reason, List<Map<String, Object>> hits) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("reason", reason);
        body.put("citationEligible", "SOURCE".equals(reason));
        body.put("pool", "SOURCE".equals(reason) ? "SOURCE" : reason);
        body.put("hits", hits);
        try {
            return mapper.writeValueAsString(body);
        }
        catch (Exception exception) {
            return "{\"status\":\"" + status + "\",\"reason\":\"" + reason + "\",\"citationEligible\":false,\"hits\":[]}";
        }
    }

    private List<Long> copyPositive(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && value > 0L).toList();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String clip(String text, int maxChars) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "…";
    }
}
