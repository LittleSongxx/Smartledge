package org.smartledge.ai.manage.support;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.smartledge.ai.manage.data.SuperAgentDocumentBlock;
import org.smartledge.ai.manage.service.DocumentParseArtifactService;
import org.smartledge.ai.manage.service.DocumentStorageService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@AllArgsConstructor
public class DocumentChunkingProfiles {
    private final DocumentMarkdownSyntaxContract syntaxContract;
    private final DocumentParseArtifactService artifacts;
    private final DocumentStorageService storage;
    private final ObjectMapper mapper;

    public record Unit(String id, String question, DocumentMarkdownSyntaxNodeCandidate questionNode,
                       List<DocumentMarkdownSyntaxNodeCandidate> answers) { }
    public record Facts(DocumentMarkdownSyntaxCandidate syntax, List<Unit> units,
                        Map<String, SuperAgentDocumentBlock> blocks) { }

    public ChunkingContract recommend(Long documentId, Long parseTaskId, DocumentMarkdownSyntaxCandidate syntax,
                                       ChunkingContract.Budget budget) {
        String profile = ChunkingContract.GENERIC;
        String reason = "QA_SOURCE_MARKDOWN_REQUIRED";
        if (syntax != null) {
            try {
                units(documentId, parseTaskId, syntax);
                profile = ChunkingContract.QA;
                reason = "QA_EXPLICIT_BOUNDARIES";
            } catch (ChunkingProfileException exception) { reason = exception.getMessage(); }
        }
        return new ChunkingContract(ChunkingContract.SCHEMA, profile, profile,
            ChunkingContract.QA.equals(profile) ? ChunkingContract.QA_RULE : "generic.v1",
            reason, parseTaskId, syntax == null ? null : syntax.getSourceSha256(), budget, false);
    }

    public Facts load(Long documentId, ChunkingContract contract, List<SuperAgentDocumentBlock> blocks) {
        var matches = artifacts.listArtifacts(documentId, contract.sourceParseTaskId()).stream()
            .filter(artifact -> "MARKDOWN_SYNTAX_JSON".equals(artifact.getArtifactType()))
            .toList();
        if (matches.size() != 1) { throw new ChunkingProfileException("PROFILE_SOURCE_MISSING", null); }
        var artifact = matches.get(0);
        if (!Objects.equals(documentId, artifact.getDocumentId())
            || !Objects.equals(contract.sourceParseTaskId(), artifact.getTaskId())) {
            throw new ChunkingProfileException("PROFILE_SOURCE_MAPPING_INVALID", null);
        }
        byte[] bytes = storage.downloadObject(artifact.getObjectName());
        if (bytes == null || !Objects.equals(DigestUtil.sha256Hex(bytes), artifact.getContentHash())) {
            throw new ChunkingProfileException("PROFILE_ARTIFACT_HASH_MISMATCH", null);
        }
        var syntax = syntaxContract.read(bytes);
        if (!Objects.equals(syntax.getSourceSha256(), contract.sourceSha256())) {
            throw new ChunkingProfileException("PROFILE_SOURCE_HASH_MISMATCH", null);
        }
        List<Unit> units = units(documentId, contract.sourceParseTaskId(), syntax);
        Map<String, SuperAgentDocumentBlock> mapped = new LinkedHashMap<>();
        for (var block : blocks) {
            try {
                var metadata = mapper.readTree(block.getMetadataJson());
                String nodeId = metadata.path("syntaxNodeId").asText();
                var node = syntax.getNodes().stream().filter(item -> item.getNodeId().equals(nodeId))
                    .findFirst().orElseThrow();
                if (block.getId() == null || !Objects.equals(documentId, block.getDocumentId())
                    || !Objects.equals(contract.sourceParseTaskId(), block.getTaskId())
                    || metadata.path("sourceSpan").path("startByte").asInt(-1) != node.getSourceSpan().getStartByte()
                    || metadata.path("sourceSpan").path("endByte").asInt(-1) != node.getSourceSpan().getEndByte()
                    || mapped.putIfAbsent(nodeId, block) != null) {
                    throw new IllegalStateException("Invalid block source");
                }
            } catch (Exception exception) {
                throw new ChunkingProfileException("PROFILE_SOURCE_MAPPING_INVALID", String.valueOf(block.getId()), exception);
            }
        }
        for (var node : topLevel(syntax)) {
            if (!mapped.containsKey(node.getNodeId())) {
                throw new ChunkingProfileException("PROFILE_SOURCE_MAPPING_INVALID", node.getNodeId());
            }
        }
        return new Facts(syntax, units, Map.copyOf(mapped));
    }

    private List<Unit> units(Long documentId, Long parseTaskId, DocumentMarkdownSyntaxCandidate syntax) {
        try { syntaxContract.validate(syntax); }
        catch (Exception exception) { throw new ChunkingProfileException("PROFILE_SOURCE_INVALID", null, exception); }
        if (!"SOURCE_MARKDOWN".equals(syntax.getSourceOrigin())) {
            throw new ChunkingProfileException("QA_SOURCE_MARKDOWN_REQUIRED", null);
        }
        List<Unit> units = new ArrayList<>();
        DocumentMarkdownSyntaxNodeCandidate question = null;
        List<DocumentMarkdownSyntaxNodeCandidate> answer = new ArrayList<>();
        boolean answerStarted = false;
        for (var node : topLevel(syntax)) {
            String text = node.getText().strip();
            boolean heading = "HEADING".equals(node.getNodeType());
            if (heading && node.getLevel() == 2 && text.startsWith("Q:")) {
                if (question != null) { units.add(unit(documentId, parseTaskId, syntax, question, answer)); }
                question = node;
                answer = new ArrayList<>();
                answerStarted = false;
                if (text.substring(2).isBlank()) { throw new ChunkingProfileException("QA_QUESTION_MISSING", node.getNodeId()); }
            } else if (heading && node.getLevel() == 3 && "A:".equals(text) && question != null && !answerStarted) {
                answerStarted = true;
            } else if (question == null || !answerStarted) {
                throw new ChunkingProfileException("QA_UNASSIGNED_CONTENT", node.getNodeId());
            } else if (heading && (node.getLevel() <= 3 || text.startsWith("Q:") || text.startsWith("A:"))) {
                throw new ChunkingProfileException("QA_AMBIGUOUS_BOUNDARY", node.getNodeId());
            } else if ("HTML_BLOCK".equals(node.getNodeType())
                || (!"CODE_BLOCK".equals(node.getNodeType()) && text.matches("(?s).*?!\\[.*"))) {
                throw new ChunkingProfileException("QA_UNSUPPORTED_MEDIA", node.getNodeId());
            } else {
                answer.add(node);
            }
        }
        if (question != null) { units.add(unit(documentId, parseTaskId, syntax, question, answer)); }
        if (units.isEmpty()) { throw new ChunkingProfileException("QA_PAIR_MISSING", null); }
        return List.copyOf(units);
    }

    private Unit unit(Long documentId, Long parseTaskId, DocumentMarkdownSyntaxCandidate syntax,
                      DocumentMarkdownSyntaxNodeCandidate question, List<DocumentMarkdownSyntaxNodeCandidate> answer) {
        if (answer.isEmpty() || answer.stream().allMatch(node -> node.getText().isBlank())) {
            throw new ChunkingProfileException("QA_ANSWER_MISSING", question.getNodeId());
        }
        String id = DigestUtil.sha256Hex(documentId + ":" + parseTaskId + ":" + syntax.getSourceSha256()
            + ":" + ChunkingContract.QA_RULE + ":" + question.getSourceSpan().getStartByte());
        return new Unit(id, question.getText().strip().substring(2).strip(), question, List.copyOf(answer));
    }

    private List<DocumentMarkdownSyntaxNodeCandidate> topLevel(DocumentMarkdownSyntaxCandidate syntax) {
        String root = syntax.getNodes().get(0).getNodeId();
        return syntax.getNodes().stream().filter(node -> root.equals(node.getParentNodeId())).toList();
    }

    public static String sourceText(DocumentMarkdownSyntaxCandidate syntax, DocumentMarkdownSyntaxNodeCandidate node) {
        byte[] bytes = syntax.getSourceText().getBytes(StandardCharsets.UTF_8);
        return new String(bytes, node.getSourceSpan().getStartByte(),
            node.getSourceSpan().getEndByte() - node.getSourceSpan().getStartByte(), StandardCharsets.UTF_8);
    }
}
