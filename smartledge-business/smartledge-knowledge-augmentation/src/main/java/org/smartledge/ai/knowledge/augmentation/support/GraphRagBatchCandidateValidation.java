package org.smartledge.ai.knowledge.augmentation.support;

import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionRequest;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionResponse;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionResponse.Entity;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionResponse.Evidence;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionResponse.Relation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Candidate isolation after the batch identity and complete response envelope have been verified. */
final class GraphRagBatchCandidateValidation {
    private GraphRagBatchCandidateValidation() {
    }

    static void filter(GraphRagExtractionRequest request, GraphRagExtractionResponse response) {
        Rejections rejections = new Rejections();
        rejections.merge(response.getMetadata(), request.getBatchId());
        Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
        request.getSegments().forEach(segment -> sources.put((String) segment.get("sourceId"), segment));
        Set<Long> chunkIds = request.getChunks().stream().map(GraphRagExtractionRequest.Chunk::getChunkId)
                .collect(Collectors.toSet());
        response.setEntities(filter(response.getEntities(), Entity::getId, "entities", rejections, entity -> {
            if (entity.getSourceChunkIds() == null || entity.getSourceChunkIds().isEmpty()
                    || !chunkIds.containsAll(entity.getSourceChunkIds())) {
                return "UNKNOWN_ENTITY_SOURCE";
            }
            if (entity.getMetadata() != null && entity.getMetadata().containsKey("sourceId")) {
                Map<String, Object> segment = sources.get(entity.getMetadata().get("sourceId"));
                if (segment == null || !entity.getSourceChunkIds().equals(List.of(longValue(segment.get("chunkId"))))) {
                    return "UNKNOWN_ENTITY_SOURCE";
                }
            }
            return length(entity.getName()) > 500 ? "ENTITY_NAME_RESOURCE_LIMIT" : null;
        }));
        response.setRelations(filter(response.getRelations(), Relation::getId, "relations", rejections,
                relation -> length(relation.getDescription()) > longValue(request.getOptions().get("maxReasonChars"))
                        ? "RELATION_REASON_RESOURCE_LIMIT" : null));
        Map<String, Relation> relations = response.getRelations().stream()
                .collect(Collectors.toMap(Relation::getId, Function.identity()));
        response.setEvidences(filter(response.getEvidences(), Evidence::getId, "evidences", rejections, evidence -> {
            Map<String, Object> meta = evidence.getMetadata();
            Map<String, Object> segment = meta == null ? null : sources.get(meta.get("sourceId"));
            if (segment == null || !Objects.equals(evidence.getChunkId(), longValue(segment.get("chunkId")))) {
                return "UNKNOWN_EVIDENCE_SOURCE";
            }
            if (!Objects.equals(longValue(meta.get("start")), longValue(segment.get("start")))
                    || !Objects.equals(longValue(meta.get("end")), longValue(segment.get("end")))) {
                return "EVIDENCE_RANGE_DRIFT";
            }
            if (present(evidence.getEntityId()) == present(evidence.getRelationId())) {
                return "EVIDENCE_OWNER_INVALID";
            }
            if (present(evidence.getRelationId())) {
                Relation relation = relations.get(evidence.getRelationId());
                // Unknown owners are diagnosed by the existing Java grounding validator.
                if (relation != null && (relation.getEvidenceIds() == null
                        || !relation.getEvidenceIds().contains(evidence.getId()))) {
                    return "EVIDENCE_REFERENCE_UNBOUND";
                }
            }
            String quote = evidence.getQuoteText();
            if (length(quote) > longValue(request.getOptions().get("maxQuoteChars"))) {
                return "QUOTE_RESOURCE_LIMIT";
            }
            String text = (String) segment.get("text");
            if (present(quote)) {
                int position = text.indexOf(quote);
                String original = request.getChunks().stream().filter(c -> c.getChunkId().equals(evidence.getChunkId()))
                        .findFirst().orElseThrow().getText();
                if (position < 0 && original.contains(quote)) {
                    return "EVIDENCE_OUTSIDE_PLANNED_SPAN";
                }
                if (position >= 0) {
                    Map<String, Object> provenance = new LinkedHashMap<>(meta);
                    long start = longValue(segment.get("start")) + text.codePointCount(0, position);
                    provenance.put("quoteStart", start);
                    provenance.put("quoteEnd", start + length(quote));
                    provenance.put("offsetUnit", "unicode-code-point");
                    evidence.setMetadata(provenance);
                }
            }
            return null;
        }));
        Map<String, Object> metadata = new LinkedHashMap<>(response.getMetadata());
        rejections.write(metadata);
        response.setMetadata(metadata);
    }

    private static <T> List<T> filter(List<T> values, Function<T, String> identity, String field,
            Rejections rejections, Function<T, String> validator) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (T value : values) {
            if (value != null && present(identity.apply(value))) {
                occurrences.merge(identity.apply(value), 1, Integer::sum);
            }
        }
        List<T> retained = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            T value = values.get(index);
            String id = value == null ? null : identity.apply(value);
            String reason;
            if (value == null) {
                reason = "CANDIDATE_MISSING";
            }
            else if (!present(id)) {
                reason = "CANDIDATE_ID_MISSING";
            }
            else if (occurrences.get(id) > 1) {
                reason = "DUPLICATE_CANDIDATE_ID";
            }
            else {
                reason = validator.apply(value);
            }
            if (reason == null) {
                retained.add(value);
            }
            else {
                rejections.add(Map.of("field", field, "index", index, "candidateId", id == null ? "" : clip(id),
                        "reason", reason));
            }
        }
        return retained;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private static Long longValue(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                ? ((Number) value).longValue() : null;
    }

    private static String clip(String text) {
        return text.substring(0, Math.min(128, text.length()));
    }

    /** Bounded samples are diagnostics only; every rejection still contributes to the count. */
    static final class Rejections {
        private long count;
        private final List<Map<String, Object>> samples = new ArrayList<>();
        private final Map<String, Long> reasonCounts = new LinkedHashMap<>();

        void add(Map<String, Object> record) {
            count++;
            reasonCounts.merge(String.valueOf(record.getOrDefault("reason", "UNKNOWN")), 1L, Long::sum);
            sample(record, null);
        }

        void merge(Map<String, Object> metadata, String batchId) {
            Long reported = longValue(metadata.get("candidateRejectionCount"));
            if (reported != null && reported > 0) {
                count += reported;
            }
            if (metadata.get("candidateRejectionReasonCounts") instanceof Map<?, ?> reportedReasons) {
                reportedReasons.forEach((reason, value) -> {
                    Long amount = longValue(value);
                    if (reason != null && amount != null && amount > 0) {
                        reasonCounts.merge(String.valueOf(reason), amount, Long::sum);
                    }
                });
            }
            if (metadata.get("candidateRejections") instanceof List<?> records) {
                for (Object record : records) {
                    if (record instanceof Map<?, ?> map) {
                        sample(map, batchId);
                    }
                }
            }
        }

        private void sample(Map<?, ?> record, String batchId) {
            if (samples.size() >= 64) {
                return;
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            for (String key : List.of("field", "index", "candidateId", "reason", "errorCount", "batchId")) {
                Object value = record.get(key);
                if (value instanceof String text) {
                    sample.put(key, clip(text));
                }
                else if (longValue(value) != null) {
                    sample.put(key, value);
                }
            }
            if (record.get("errors") instanceof List<?> errors) {
                sample.put("errors", errors.stream().limit(8).map(error -> {
                    if (!(error instanceof Map<?, ?> detail)) {
                        return Map.of();
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    if (detail.get("type") instanceof String type) {
                        item.put("type", clip(type));
                    }
                    if (detail.get("location") instanceof List<?> location) {
                        item.put("location", location.stream().limit(8)
                                .map(part -> clip(String.valueOf(part))).toList());
                    }
                    return item;
                }).toList());
            }
            if (batchId != null) {
                sample.put("batchId", batchId);
            }
            samples.add(sample);
        }

        long count() {
            return count;
        }

        void write(Map<String, Object> metadata) {
            metadata.put("candidateRejectionCount", count);
            metadata.put("candidateRejectionReasonCounts", new LinkedHashMap<>(reasonCounts));
            metadata.put("candidateRejections", new ArrayList<>(samples));
            metadata.put("candidateRejectionsTruncated", count > samples.size());
        }
    }
}
