package org.smartledge.ai.ragtools.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionRequest;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionResponse;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagExtractionPort;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagToolException;
import org.smartledge.ai.ragtools.client.RagToolsClient;
import org.smartledge.ai.ragtools.client.RagToolsTransportException;
import org.smartledge.ai.ragtools.model.RagToolsGraphExtractRequest;
import org.smartledge.ai.ragtools.model.RagToolsGraphExtractResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/** Maps the augmentation-owned GraphRAG seam to the Python HTTP protocol. */
@Component
public final class RagToolsGraphRagExtractionAdapter implements GraphRagExtractionPort {

    private final RagToolsClient client;

    public RagToolsGraphRagExtractionAdapter(RagToolsClient client) {
        this.client = client;
    }

    @Override
    public GraphRagExtractionResponse extract(GraphRagExtractionRequest request) {
        try {
            RagToolsGraphExtractResponse response = client.extractGraph(toTransport(request));
            return response == null ? null : fromTransport(response);
        }
        catch (RagToolsTransportException failure) {
            LinkedHashMap<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("layer", "JAVA_PYTHON");
            detail.put("batchId", request == null || request.getBatchId() == null ? "plan" : request.getBatchId());
            if (failure.getStatus() != null) {
                detail.put("httpStatus", failure.getStatus());
            }
            GraphRagToolException.Category category = GraphRagToolException.Category.PROTOCOL;
            boolean structuredError = failure.getBodyPreview() != null
                    && failure.getBodyPreview().stripLeading().startsWith("{");
            try {
                JsonNode error = new ObjectMapper().readTree(failure.getBodyPreview()).path("detail");
                structuredError |= error.isObject();
                if (error.isArray()) {
                    detail.put("validationErrors", validationErrors(error));
                    structuredError = true;
                }
                if ("graph-tool-error.v1".equals(error.path("schemaVersion").asText())) {
                    category = GraphRagToolException.Category.valueOf(error.path("category").asText());
                    detail.put("layer", "PYTHON_MODEL");
                    for (String key : List.of("errorCode", "causeType", "parseStage", "errorType")) {
                        String value = error.path(key).asText("");
                        if (value.matches("[A-Za-z0-9_.-]{1,100}")) {
                            detail.put(key, value);
                        }
                    }
                    for (String key : List.of("resource", "candidateId")) {
                        String value = error.path(key).asText("");
                        if (value.matches("[A-Za-z0-9_.:-]{1,128}")) {
                            detail.put(key, value);
                        }
                    }
                    for (String key : List.of("measurementKind", "actualAvailability")) {
                        String value = error.path(key).asText("");
                        if (value.matches("[A-Z_]{1,40}")) {
                            detail.put(key, value);
                        }
                    }
                    JsonNode parameterKeys = error.path("parameterKeys");
                    if (parameterKeys.isArray()) {
                        List<String> keys = new ArrayList<>();
                        for (JsonNode parameterKey : parameterKeys) {
                            if (keys.size() >= 12) {
                                break;
                            }
                            String value = parameterKey.asText("");
                            if (value.matches("[A-Za-z0-9_.-]{1,128}")) {
                                keys.add(value);
                            }
                        }
                        if (!keys.isEmpty()) {
                            detail.put("parameterKeys", List.copyOf(keys));
                        }
                    }
                    for (String key : List.of("actualCount", "configuredLimit", "candidateIndex",
                            "actualLength")) {
                        if (error.path(key).isIntegralNumber() && error.path(key).longValue() >= 0) {
                            detail.put(key, error.path(key).longValue());
                        }
                    }
                    for (String key : List.of("actualIsLowerBound")) {
                        if (error.path(key).isBoolean()) {
                            detail.put(key, error.path(key).booleanValue());
                        }
                    }
                    for (String key : List.of("upstreamStatus", "retryAfterMillis", "errorOffset",
                            "responseBytes", "errorCount")) {
                        if (error.path(key).isIntegralNumber()) {
                            detail.put(key, error.path(key).longValue());
                        }
                    }
                    LinkedHashMap<String, Long> usage = new LinkedHashMap<String, Long>();
                    for (String key : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
                        JsonNode value = error.path("usage").path(key);
                        if (value.canConvertToLong() && value.isIntegralNumber() && value.longValue() >= 0) {
                            usage.put(key, value.longValue());
                        }
                    }
                    if (!usage.isEmpty()) {
                        detail.put("usage", usage);
                    }
                }
            }
            catch (Exception ignored) {
                /* malformed error contracts are not retryable */
            }
            if (failure.getStatus() == null) {
                category = GraphRagToolException.Category.UNKNOWN;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof IOException && category == GraphRagToolException.Category.UNKNOWN) {
                        category = GraphRagToolException.Category.CONNECTION;
                    }
                    if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                            || cause instanceof TimeoutException) {
                        category = GraphRagToolException.Category.READ_TIMEOUT;
                    }
                    if (cause instanceof HttpConnectTimeoutException) {
                        category = GraphRagToolException.Category.CONNECT_TIMEOUT;
                    }
                    if (cause instanceof BufferOverflowException) {
                        category = GraphRagToolException.Category.RESOURCE_LIMIT;
                    }
                    if (cause instanceof InterruptedException) {
                        category = GraphRagToolException.Category.CANCELLED;
                    }
                    if (cause instanceof SSLException || cause instanceof IllegalArgumentException) {
                        category = GraphRagToolException.Category.CONFIGURATION;
                    }
                    if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                        category = GraphRagToolException.Category.CONNECTION;
                    }
                    detail.put("causeType", cause.getClass().getSimpleName());
                }
            }
            else if (!structuredError) {
                category = switch (failure.getStatus()) {
                case 401, 403 -> GraphRagToolException.Category.AUTHENTICATION;
                case 429 -> GraphRagToolException.Category.RATE_LIMIT;
                case 500, 502, 503, 504 -> GraphRagToolException.Category.UPSTREAM_SERVER;
                default -> GraphRagToolException.Category.PROTOCOL;
                };
            }
            throw new GraphRagToolException(category, detail, failure);
        }
    }

    private List<Map<String, Object>> validationErrors(JsonNode errors) {
        List<Map<String, Object>> result = new ArrayList<>();
        int count = 0;
        for (JsonNode error : errors) {
            if (count++ >= 8) {
                break;
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            String type = error.path("type").asText("");
            if (type.matches("[A-Za-z0-9_.-]{1,100}")) {
                item.put("type", type);
            }
            JsonNode location = error.path("loc");
            if (location.isArray() && location.size() <= 8) {
                List<Object> parts = new ArrayList<>();
                for (JsonNode part : location) {
                    if (part.isIntegralNumber() && part.canConvertToLong()) {
                        parts.add(part.longValue());
                    }
                    else if (part.isTextual() && part.asText().matches("[A-Za-z0-9_.-]{1,64}")) {
                        parts.add(part.asText());
                    }
                    else {
                        parts.add("[redacted]");
                    }
                }
                item.put("location", parts);
            }
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    private RagToolsGraphExtractRequest toTransport(GraphRagExtractionRequest request) {
        if (request == null) {
            return null;
        }
        RagToolsGraphExtractRequest result = new RagToolsGraphExtractRequest();
        result.setSchemaVersion(request.getSchemaVersion());
        result.setOperation(request.getOperation());
        result.setSourceParseTaskId(request.getSourceParseTaskId());
        result.setPlanId(request.getPlanId());
        result.setExcludedBlankChunkIds(request.getExcludedBlankChunkIds());
        result.setInputFingerprint(request.getInputFingerprint());
        result.setConfigurationFingerprint(request.getConfigurationFingerprint());
        result.setPlanFingerprint(request.getPlanFingerprint());
        result.setBatchId(request.getBatchId());
        result.setBudgetMillis(request.getBudgetMillis());
        result.setOptions(request.getOptions());
        result.setSegments(request.getSegments());
        result.setDocumentId(request.getDocumentId());
        result.setTaskId(request.getTaskId());
        result.setChunks(request.getChunks() == null ? null : request.getChunks().stream().map(chunk -> {
            RagToolsGraphExtractRequest.Chunk mapped = new RagToolsGraphExtractRequest.Chunk();
            mapped.setChunkId(chunk.getChunkId());
            mapped.setParentBlockId(chunk.getParentBlockId());
            mapped.setChunkNo(chunk.getChunkNo());
            mapped.setChunkType(chunk.getChunkType());
            mapped.setTitle(chunk.getTitle());
            mapped.setSectionPath(chunk.getSectionPath());
            mapped.setPageNo(chunk.getPageNo());
            mapped.setPageRange(chunk.getPageRange());
            mapped.setBboxJson(chunk.getBboxJson());
            mapped.setText(chunk.getText());
            mapped.setContentWithWeight(chunk.getContentWithWeight());
            mapped.setSourceBlockIds(chunk.getSourceBlockIds());
            mapped.setStructuredTables(chunk.getStructuredTables() == null ? List.of()
                    : chunk.getStructuredTables().stream().map(table -> {
                        RagToolsGraphExtractRequest.StructuredTable mappedTable =
                                new RagToolsGraphExtractRequest.StructuredTable();
                        mappedTable.setTableId(table.getTableId());
                        mappedTable.setBlockId(table.getBlockId());
                        mappedTable.setTableNo(table.getTableNo());
                        mappedTable.setTitle(table.getTitle());
                        mappedTable.setColumns(table.getColumns() == null ? List.of()
                                : table.getColumns().stream().map(column ->
                                        new RagToolsGraphExtractRequest.StructuredTableColumn(column.getColumnNo(),
                                                column.getColumnName())).toList());
                        mappedTable.setRows(table.getRows() == null ? List.of()
                                : table.getRows().stream().map(row ->
                                        new RagToolsGraphExtractRequest.StructuredTableRow(row.getRowNo(),
                                                row.getRowText(), row.getCells() == null ? List.of()
                                                        : row.getCells().stream().map(cell ->
                                                                new RagToolsGraphExtractRequest.StructuredTableCell(
                                                                        cell.getColumnNo(), cell.getText())).toList()))
                                        .toList());
                        return mappedTable;
                    }).toList());
            mapped.setMetadata(chunk.getMetadata());
            return mapped;
        }).toList());
        return result;
    }

    private GraphRagExtractionResponse fromTransport(RagToolsGraphExtractResponse source) {
        GraphRagExtractionResponse result = new GraphRagExtractionResponse();
        result.setEntities(source.getEntities() == null ? null : source.getEntities().stream().map(item -> {
            GraphRagExtractionResponse.Entity mapped = new GraphRagExtractionResponse.Entity();
            mapped.setId(item.getId());
            mapped.setName(item.getName());
            mapped.setNormalizedName(item.getNormalizedName());
            mapped.setAliases(item.getAliases());
            mapped.setType(item.getType());
            mapped.setDescription(item.getDescription());
            mapped.setConfidence(item.getConfidence());
            mapped.setSourceChunkIds(item.getSourceChunkIds());
            mapped.setEvidenceIds(item.getEvidenceIds());
            mapped.setMetadata(item.getMetadata());
            return mapped;
        }).toList());
        result.setRelations(source.getRelations() == null ? null : source.getRelations().stream().map(item -> {
            GraphRagExtractionResponse.Relation mapped = new GraphRagExtractionResponse.Relation();
            mapped.setId(item.getId());
            mapped.setSourceEntityId(item.getSourceEntityId());
            mapped.setTargetEntityId(item.getTargetEntityId());
            mapped.setRelationType(item.getRelationType());
            mapped.setDescription(item.getDescription());
            mapped.setWeight(item.getWeight());
            mapped.setConfidence(item.getConfidence());
            mapped.setEvidenceIds(item.getEvidenceIds());
            mapped.setMetadata(item.getMetadata());
            return mapped;
        }).toList());
        result.setEvidences(source.getEvidences() == null ? null : source.getEvidences().stream().map(item -> {
            GraphRagExtractionResponse.Evidence mapped = new GraphRagExtractionResponse.Evidence();
            mapped.setId(item.getId());
            mapped.setEntityId(item.getEntityId());
            mapped.setRelationId(item.getRelationId());
            mapped.setChunkId(item.getChunkId());
            mapped.setParentBlockId(item.getParentBlockId());
            mapped.setQuoteText(item.getQuoteText());
            mapped.setPageNo(item.getPageNo());
            mapped.setPageRange(item.getPageRange());
            mapped.setBboxJson(item.getBboxJson());
            mapped.setSectionPath(item.getSectionPath());
            mapped.setMetadata(item.getMetadata());
            return mapped;
        }).toList());
        result.setCommunities(source.getCommunities() == null ? null : source.getCommunities().stream().map(item -> {
            GraphRagExtractionResponse.Community mapped = new GraphRagExtractionResponse.Community();
            mapped.setId(item.getId());
            mapped.setTitle(item.getTitle());
            mapped.setSummary(item.getSummary());
            mapped.setEntityIds(item.getEntityIds());
            mapped.setRelationIds(item.getRelationIds());
            mapped.setEvidenceIds(item.getEvidenceIds());
            mapped.setMetadata(item.getMetadata());
            return mapped;
        }).toList());
        result.setMetadata(source.getMetadata());
        return result;
    }
}
