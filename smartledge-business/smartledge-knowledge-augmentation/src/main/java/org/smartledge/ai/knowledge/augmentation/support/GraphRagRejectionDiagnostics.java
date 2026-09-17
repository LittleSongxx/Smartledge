package org.smartledge.ai.knowledge.augmentation.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.knowledge.augmentation.config.GraphRagDiagnosticProperties;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagExtractionAdvice.EntityItem;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagExtractionAdvice.EvidenceItem;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagExtractionAdvice.RelationItem;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagExtractionAdvice;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagExtractionContext.ChunkItem;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagExtractionContext;
import org.springframework.stereotype.Component;

/** Local, bounded audit material. It never decides candidate eligibility or restores a build. */
@Slf4j
@Component
public final class GraphRagRejectionDiagnostics {
    private final GraphRagDiagnosticProperties limits;
    private final ObjectMapper mapper;

    public GraphRagRejectionDiagnostics(GraphRagDiagnosticProperties properties, ObjectMapper mapper) {
        properties.validate();
        this.mapper = mapper;
        this.limits = mapper.convertValue(properties, GraphRagDiagnosticProperties.class);
    }

    public synchronized Map<String, Object> write(GraphRagExtractionContext context, GraphRagExtractionAdvice advice,
            List<String> reasons) {
        if (!limits.isEnabled() || reasons.isEmpty()) {
            return Map.of("status", limits.isEnabled() ? "NO_REJECTIONS" : "DISABLED");
        }
        Path file = null;
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", "graph-rejection-diagnostics.v1");
            report.put("documentId", context.getDocumentId());
            report.put("taskId", context.getTaskId());
            report.put("reasonCount", reasons.size());
            report.put("limits", mapper.convertValue(limits, Map.class));
            List<Map<String, Object>> records = new ArrayList<>();
            report.put("records", records);
            report.put("truncated", false);
            for (String reason : reasons) {
                records.add(record(reason, context, advice));
                if (mapper.writeValueAsBytes(report).length > limits.getMaxFileBytes() - 128) {
                    records.remove(records.size() - 1);
                    break;
                }
            }
            boolean truncated = records.size() < reasons.size();
            report.put("truncated", truncated);
            report.put("recordCount", records.size());
            byte[] bytes = mapper.writeValueAsBytes(report);
            if (bytes.length > limits.getMaxFileBytes()) {
                throw new IOException("Diagnostic envelope exceeds configured byte limit");
            }
            Path directory = Path.of(limits.getDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            retainFiles(directory);
            file = directory.resolve("graph-rejections-" + context.getDocumentId() + "-" + context.getTaskId() + "-"
                    + UUID.randomUUID() + ".json");
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Map<String, Object> summary = Map.of("status", "WRITTEN", "path", file.toString(), "reasonCount",
                    reasons.size(), "recordCount", records.size(), "truncated", truncated, "bytes", bytes.length);
            log.info("GraphRAG 拒绝候选诊断: documentId={}, taskId={}, summary={}", context.getDocumentId(),
                    context.getTaskId(), summary);
            return summary;
        }
        catch (IOException | RuntimeException failure) {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                }
                catch (IOException ignored) {
                    // A failed write must never change the graph outcome.
                }
            }
            log.warn("GraphRAG 拒绝诊断写入失败: documentId={}, taskId={}, causeType={}", context.getDocumentId(),
                    context.getTaskId(), failure.getClass().getSimpleName());
            return Map.of("status", "WRITE_FAILED", "causeType", failure.getClass().getSimpleName());
        }
    }

    private Map<String, Object> record(String reason, GraphRagExtractionContext context,
            GraphRagExtractionAdvice advice) {
        Map<String, Object> record = new LinkedHashMap<>();
        text(record, "reason", reason);
        EvidenceItem rejectedEvidence = advice.getEvidences().stream().filter(Objects::nonNull)
                .filter(evidence -> identifies(reason, "evidenceId", evidence.getId())
                        || reason.equals("ENTITY_EVIDENCE_MENTION_MISSING:" + evidence.getId()))
                .findFirst().orElse(null);
        RelationItem relation = advice.getRelations().stream().filter(Objects::nonNull)
                .filter(candidate -> identifies(reason, "relationId", candidate.getId()) || (rejectedEvidence != null
                        && Objects.equals(candidate.getId(), rejectedEvidence.getRelationId())))
                .findFirst().orElse(null);
        if (relation != null) {
            text(record, "relationId", relation.getId());
            int separator = relation.getId() == null ? -1 : relation.getId().indexOf(':');
            text(record, "batchId", separator < 0 ? "" : relation.getId().substring(0, separator));
            text(record, "predicateQuoteText", relation.getPredicateQuoteText());
            text(record, "supportMode", relation.getSupportMode());
            record.put("source", entity(relation.getSourceEntityId(), advice));
            record.put("target", entity(relation.getTargetEntityId(), advice));
        }
        List<Map<String, Object>> evidenceRecords = new ArrayList<>();
        int matched = 0;
        List<EvidenceItem> orderedEvidence = new ArrayList<>(advice.getEvidences());
        if (rejectedEvidence != null) {
            orderedEvidence.remove(rejectedEvidence);
            orderedEvidence.add(0, rejectedEvidence);
        }
        for (EvidenceItem evidence : orderedEvidence) {
            if (evidence == null) {
                continue;
            }
            if (evidence != rejectedEvidence
                    && (relation == null || !Objects.equals(relation.getId(), evidence.getRelationId()))) {
                continue;
            }
            matched++;
            Map<String, Object> item = new LinkedHashMap<>();
            text(item, "evidenceId", evidence.getId());
            text(item, "entityId", evidence.getEntityId());
            text(item, "relationId", evidence.getRelationId());
            text(item, "quoteText", evidence.getQuoteText());
            item.put("chunkId", evidence.getChunkId());
            item.put("rejectedEvidence", evidence == rejectedEvidence);
            if (context.getChunks() != null) {
                ChunkItem chunk = context.getChunks().stream().filter(Objects::nonNull)
                        .filter(candidate -> Objects.equals(candidate.getChunkId(), evidence.getChunkId())).findFirst()
                        .orElse(null);
                if (chunk != null) {
                    text(item, "chunkText", chunk.getText());
                    item.put("chunkNo", chunk.getChunkNo());
                    String quote = evidence.getQuoteText();
                    item.put("exactQuoteInChunk", quote != null && !quote.isBlank() && chunk.getText() != null
                            && chunk.getText().contains(quote));
                }
            }
            evidenceRecords.add(item);
        }
        record.put("evidences", evidenceRecords);
        record.put("matchingEvidenceCount", matched);
        record.put("evidencesTruncated", matched > evidenceRecords.size());
        if (relation == null && rejectedEvidence != null) {
            record.put("entity", entity(rejectedEvidence.getEntityId(), advice));
        }
        return record;
    }

    private Map<String, Object> entity(String id, GraphRagExtractionAdvice advice) {
        Map<String, Object> result = new LinkedHashMap<>();
        text(result, "id", id);
        EntityItem entity = advice.getEntities().stream().filter(Objects::nonNull)
                .filter(item -> Objects.equals(id, item.getId())).findFirst().orElse(null);
        if (entity != null) {
            text(result, "name", entity.getName());
            text(result, "normalizedName", entity.getNormalizedName());
            text(result, "aliasesJson", mapper.valueToTree(entity.getAliases()).toString());
        }
        return result;
    }

    private boolean identifies(String reason, String field, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return reason.contains(field + "=" + id + ",") || reason.contains(field + "=" + id + "]");
    }

    private void text(Map<String, Object> target, String key, String value) {
        if (value == null) {
            target.put(key, null);
            return;
        }
        int count = value.codePointCount(0, value.length());
        boolean truncated = count > limits.getMaxTextChars();
        target.put(key, truncated ? value.substring(0, value.offsetByCodePoints(0, limits.getMaxTextChars())) : value);
        target.put(key + "Truncated", truncated);
    }

    private void retainFiles(Path directory) throws IOException {
        List<Path> owned;
        try (Stream<Path> files = Files.list(directory)) {
            owned = files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString()
                            .matches("graph-rejections-[0-9]+-[0-9]+-[0-9a-f-]{36}\\.json"))
                    .sorted(Comparator.comparingLong(path -> path.toFile().lastModified())).toList();
        }
        for (int index = 0; index <= owned.size() - limits.getMaxFiles(); index++) {
            Files.delete(owned.get(index));
        }
    }
}
