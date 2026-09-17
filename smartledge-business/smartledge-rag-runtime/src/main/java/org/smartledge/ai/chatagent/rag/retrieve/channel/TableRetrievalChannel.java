package org.smartledge.ai.chatagent.rag.retrieve.channel;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.chatagent.rag.model.RetrievalExecutionRequest;
import org.smartledge.ai.chatagent.rag.model.DocumentTableQueryPlan;
import org.smartledge.ai.chatagent.rag.service.DocumentTableQueryPlanner;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableDescriptor;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableQuery;
import org.smartledge.ai.rag.runtime.model.table.DocumentTableQueryResult;
import org.smartledge.ai.rag.runtime.port.DocumentEvidencePort;
import org.smartledge.ai.rag.runtime.port.DocumentTablePort;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.enums.RetrievalChannelEnum;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class TableRetrievalChannel implements RetrievalChannel {

    private static final String SOURCE_TYPE = "DOCUMENT_TABLE";
    private static final double TABLE_QUERY_SCORE = 1D;

    private final DocumentTablePort tableStructureService;
    private final DocumentTableQueryPlanner tableQueryPlanner;
    private final DocumentEvidencePort documentKnowledgeService;

    public TableRetrievalChannel(DocumentTablePort tableStructureService,
                                 DocumentTableQueryPlanner tableQueryPlanner,
                                 DocumentEvidencePort documentKnowledgeService) {
        this.tableStructureService = tableStructureService;
        this.tableQueryPlanner = tableQueryPlanner;
        this.documentKnowledgeService = documentKnowledgeService;
    }

    @Override
    public String channelName() {
        return RetrievalChannelEnum.TABLE.getName();
    }

    @Override
    public RetrievalChannelResult retrieve(RetrievalExecutionRequest request) {
        Map<String, Object> configSnapshot = baseConfigSnapshot(request);
        List<DocumentTableDescriptor> tables;
        try {
            tables = tableStructureService.listTables(request.documentScope(), request.taskScope());
        }
        catch (IllegalArgumentException | IllegalStateException exception) {
            configSnapshot.put("status", "LINEAGE_REJECTED");
            return new RetrievalChannelResult(channelName(), List.of(), configSnapshot, exception.getMessage());
        }
        configSnapshot.put("tableCount", tables.size());
        configSnapshot.put("sourceParseTaskIds", tables.stream()
            .map(DocumentTableDescriptor::getTaskId)
            .filter(Objects::nonNull)
            .distinct()
            .toList());
        Optional<DocumentTableQueryPlan> queryPlan = tableQueryPlanner.plan(request.executionQuery(), tables);
        if (queryPlan.isEmpty()) {
            configSnapshot.put("status", tables.isEmpty() ? "NO_TABLES" : "PLAN_REJECTED");
            return new RetrievalChannelResult(channelName(), List.of(), configSnapshot, "");
        }

        DocumentTableQueryPlan planned = queryPlan.get();
        DocumentTableQueryResult result = tableStructureService.query(planned.getQuery());
        validateResultLineage(request, planned, result);
        Map<Long, KnowledgeDocumentDescriptor> documentDescriptors = resolveDocumentDescriptors(request);
        if (!documentDescriptors.containsKey(result.getDocumentId())) {
            throw new IllegalStateException("table query result violates frozen document scope descriptor");
        }
        RetrievalDocument document = buildEvidenceDocument(request.executionQuery(), planned, result, documentDescriptors);
        configSnapshot.put("status", "EXECUTED");
        configSnapshot.put("selectedTableId", result.getTableId());
        configSnapshot.put("selectedIndexTaskId", planned.getTable().getIndexTaskId());
        configSnapshot.put("selectedSourceParseTaskId", result.getTaskId());
        configSnapshot.put("operation", result.getOperation());
        configSnapshot.put("selectedColumns", planned.getQuery().getSelectedColumns() == null
            ? List.of()
            : planned.getQuery().getSelectedColumns());
        configSnapshot.put("filterCount", planned.getQuery().getFilters() == null
            ? 0
            : planned.getQuery().getFilters().size());
        return new RetrievalChannelResult(channelName(), List.of(document), configSnapshot, "");
    }

    private Map<String, Object> baseConfigSnapshot(RetrievalExecutionRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", "table-retrieval-lineage.v1");
        snapshot.put("documentIds", request.documentScope());
        snapshot.put("indexTaskIds", request.taskScope());
        return snapshot;
    }

    private void validateResultLineage(RetrievalExecutionRequest request,
                                       DocumentTableQueryPlan plan,
                                       DocumentTableQueryResult result) {
        DocumentTableDescriptor table = plan == null ? null : plan.getTable();
        DocumentTableQuery query = plan == null ? null : plan.getQuery();
        if (table == null || query == null || result == null
            || result.getTableId() == null || result.getDocumentId() == null
            || result.getTaskId() == null || result.getBlockId() == null
            || table.getIndexTaskId() == null) {
            throw new IllegalStateException("table query result violates frozen task lineage");
        }
        boolean scopedPair = false;
        for (int index = 0; index < request.documentScope().size(); index++) {
            if (Objects.equals(request.documentScope().get(index), result.getDocumentId())
                && Objects.equals(request.taskScope().get(index), table.getIndexTaskId())) {
                scopedPair = true;
                break;
            }
        }
        if (!scopedPair
            || !Objects.equals(query.getTableId(), table.getTableId())
            || !Objects.equals(result.getTableId(), table.getTableId())
            || !Objects.equals(result.getDocumentId(), table.getDocumentId())
            || !Objects.equals(result.getTaskId(), table.getTaskId())
            || !Objects.equals(result.getBlockId(), table.getBlockId())
            || !Objects.equals(result.getOperation(), query.getOperation().name())) {
            throw new IllegalStateException("table query result violates frozen task lineage");
        }
    }

    private RetrievalDocument buildEvidenceDocument(String subQuestion,
                                           DocumentTableQueryPlan queryPlan,
                                           DocumentTableQueryResult result,
                                           Map<Long, KnowledgeDocumentDescriptor> documentDescriptors) {
        KnowledgeDocumentDescriptor descriptor = documentDescriptors.get(result.getDocumentId());
        String documentName = StrUtil.blankToDefault(descriptor == null ? null : descriptor.getDocumentName(), "文档表格");
        String text = renderEvidenceText(subQuestion, queryPlan, result);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, SOURCE_TYPE);
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channelName());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, TABLE_QUERY_SCORE);
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, result.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, documentName);
        if (descriptor != null) {
            putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, descriptor.getKnowledgeBaseId());
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, StrUtil.blankToDefault(descriptor.getKnowledgeBaseName(), ""));
        }
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, result.getTaskId());
        metadata.put(DocumentKnowledgeMetadataKeys.INDEX_TASK_ID, queryPlan.getTable().getIndexTaskId());
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_PARSE_TASK_ID, result.getTaskId());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, StrUtil.blankToDefault(result.getSectionPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, result.getPageNo());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, StrUtil.blankToDefault(result.getPageRange(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, StrUtil.blankToDefault(result.getBboxJson(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, result.getBlockId() == null ? "" : "[" + result.getBlockId() + "]");
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TABLE_QUERY");
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, StrUtil.blankToDefault(result.getTableTitle(), "结构化表格"));
        metadata.put(
            DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET,
            StrUtil.blankToDefault(result.getEvidenceText(), "")
        );
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_ID, result.getTableId());
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_NO, result.getTableNo());
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_TITLE, StrUtil.blankToDefault(result.getTableTitle(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_OPERATION, StrUtil.blankToDefault(result.getOperation(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_METRIC_COLUMN, StrUtil.blankToDefault(queryPlan.getQuery().getMetricColumn(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_GROUP_BY_COLUMN, StrUtil.blankToDefault(queryPlan.getQuery().getGroupByColumn(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_MATCHED_ROW_COUNT, result.getMatchedRowCount());
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_IDS, emptyListIfNull(result.getEvidenceRowIds()));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_NOS, emptyListIfNull(result.getEvidenceRowNos()));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_COLUMN_IDS, emptyListIfNull(result.getEvidenceColumnIds()));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_COLUMN_NOS, emptyListIfNull(result.getEvidenceColumnNos()));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_COLUMN_NAMES, emptyListIfNull(result.getEvidenceColumnNames()));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_IDS, emptyListIfNull(result.getEvidenceCellIds()));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_COORDINATES, emptyListIfNull(result.getEvidenceCellCoordinates()));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_BBOX_JSONS, emptyListIfNull(result.getEvidenceCellBboxJsons()));

        return RetrievalDocument.builder()
            .id("table-" + result.getTableId() + "-" + Math.abs(StrUtil.blankToDefault(subQuestion, "").hashCode()))
            .text(text)
            .metadata(metadata)
            .score(TABLE_QUERY_SCORE)
            .build();
    }

    private String renderEvidenceText(String subQuestion,
                                      DocumentTableQueryPlan queryPlan,
                                      DocumentTableQueryResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("[结构化表格查询]\n");
        builder.append("用户问题：").append(StrUtil.blankToDefault(subQuestion, "")).append('\n');
        builder.append("匹配原因：").append(StrUtil.blankToDefault(queryPlan.getReason(), "")).append('\n');
        builder.append("表格：").append(StrUtil.blankToDefault(result.getTableTitle(), "表格")).append('\n');
        if (StrUtil.isNotBlank(result.getSectionPath())) {
            builder.append("章节：").append(result.getSectionPath()).append('\n');
        }
        builder.append("查询计划：").append(renderQuery(queryPlan.getQuery())).append("\n\n");
        builder.append(StrUtil.blankToDefault(result.getEvidenceText(), ""));
        return builder.toString().trim();
    }

    private String renderQuery(DocumentTableQuery query) {
        StringBuilder builder = new StringBuilder();
        builder.append(query.getOperation().name());
        if (StrUtil.isNotBlank(query.getMetricColumn())) {
            builder.append(" metric=").append(query.getMetricColumn());
        }
        if (StrUtil.isNotBlank(query.getGroupByColumn())) {
            builder.append(" groupBy=").append(query.getGroupByColumn());
        }
        if (query.getSelectedColumns() != null && !query.getSelectedColumns().isEmpty()) {
            builder.append(" selectedColumns=").append(String.join(",", query.getSelectedColumns()));
        }
        if (query.getFilters() != null && !query.getFilters().isEmpty()) {
            builder.append(" filters=");
            builder.append(query.getFilters().stream()
                .map(filter -> filter.getColumn() + " " + filter.getOperator().name() + " " + filter.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElse(""));
        }
        return builder.toString();
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

    private List<?> emptyListIfNull(List<?> values) {
        return values == null ? List.of() : values;
    }
}
