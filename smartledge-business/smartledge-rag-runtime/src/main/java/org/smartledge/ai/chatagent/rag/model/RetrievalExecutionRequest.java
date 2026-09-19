package org.smartledge.ai.chatagent.rag.model;

import org.smartledge.enums.KnowledgeBaseSelectionMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable request compiled once for a single RetrievalPlan execution query.
 *
 * <p>It is the only channel-facing retrieval contract. Storage-specific request projection may
 * copy these values, but must not reinterpret query, scope, filters or channel policy.</p>
 */
public record RetrievalExecutionRequest(
    int subQuestionIndex,
    String sourceQuestion,
    String normalizedQuery,
    String executionQuery,
    List<String> contextHints,
    KnowledgeBaseSelectionMode scopeMode,
    List<Long> knowledgeBaseIds,
    List<Long> allowedDocumentScope,
    List<Long> documentScope,
    List<Long> taskScope,
    Filters filters,
    List<ChannelSpec> channels,
    TableSpec tableIntent,
    GraphSpec graphIntent,
    RaptorSpec raptorIntent
) {

    public RetrievalExecutionRequest {
        contextHints = immutableList(contextHints);
        knowledgeBaseIds = immutableList(knowledgeBaseIds);
        allowedDocumentScope = immutableList(allowedDocumentScope);
        documentScope = immutableList(documentScope);
        taskScope = immutableList(taskScope);
        filters = Objects.requireNonNull(filters, "Execution request filters are required");
        channels = immutableList(channels);
        tableIntent = Objects.requireNonNull(tableIntent, "Execution request table intent is required");
        graphIntent = Objects.requireNonNull(graphIntent, "Execution request graph intent is required");
        raptorIntent = Objects.requireNonNull(raptorIntent, "Execution request raptor intent is required");
        validate(subQuestionIndex, executionQuery, scopeMode, documentScope, taskScope, channels);
    }

    public static RetrievalExecutionRequest compile(RetrievalPlan plan, RetrievalExecutionQuery query) {
        Objects.requireNonNull(plan, "RetrievalPlan is required");
        Objects.requireNonNull(query, "RetrievalExecutionQuery is required");
        plan.validateForExecution();
        RetrievalExecutionQuery plannedQuery = requirePlannedQuery(plan, query.getIndex());
        if (!sameQuery(plannedQuery, query)) {
            throw new IllegalArgumentException("Execution query does not match RetrievalPlan question plan");
        }
        return new RetrievalExecutionRequest(
            query.getIndex(),
            query.getSourceQuestion(),
            query.getNormalizedQuery(),
            query.getExecutionQuery(),
            query.getContextHints(),
            plan.getScopeMode(),
            plan.getKnowledgeBaseIds(),
            plan.getAllowedDocumentScope(),
            plan.getDocumentScope(),
            plan.getTaskScope(),
            Filters.from(plan.getMetadataFilters()),
            plan.getChannels().stream().map(ChannelSpec::from).toList(),
            TableSpec.from(plan.getTableIntent()),
            GraphSpec.from(plan.getGraphIntent()),
            RaptorSpec.from(plan.getRaptorIntent())
        );
    }

    public static List<RetrievalExecutionRequest> compile(RetrievalPlan plan) {
        Objects.requireNonNull(plan, "RetrievalPlan is required");
        plan.validateForExecution();
        return plan.getQuestionPlan().getExecutionQueries().stream()
            .map(query -> compile(plan, query))
            .toList();
    }

    public List<ChannelSpec> enabledChannels() {
        return channels.stream().filter(ChannelSpec::enabled).toList();
    }

    public ChannelSpec requireChannel(String channelName) {
        return channels.stream()
            .filter(channel -> Objects.equals(channelName, channel.channelName()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Retrieval channel is absent from execution request: " + channelName));
    }

    public Reconciliation reconcile(RetrievalPlan plan) {
        Objects.requireNonNull(plan, "RetrievalPlan is required for reconciliation");
        RetrievalExecutionQuery plannedQuery = findPlannedQuery(plan, subQuestionIndex);
        Filters plannedFilters = Filters.from(plan.getMetadataFilters());
        TableSpec plannedTable = TableSpec.from(plan.getTableIntent());
        GraphSpec plannedGraph = GraphSpec.from(plan.getGraphIntent());
        RaptorSpec plannedRaptor = RaptorSpec.from(plan.getRaptorIntent());
        Map<String, ChannelSpec> plannedChannels = channelMap(
            plan.getChannels() == null ? List.of() : plan.getChannels().stream().map(ChannelSpec::from).toList()
        );
        Map<String, ChannelSpec> requestChannels = channelMap(channels);
        LinkedHashMap<String, FieldComparison> fields = new LinkedHashMap<>();

        compare(fields, "query.index", plannedQuery == null ? null : plannedQuery.getIndex(), subQuestionIndex);
        compare(fields, "query.sourceQuestion", plannedQuery == null ? null : plannedQuery.getSourceQuestion(), sourceQuestion);
        compare(fields, "query.normalizedQuery", plannedQuery == null ? null : plannedQuery.getNormalizedQuery(), normalizedQuery);
        compare(fields, "query.executionQuery", plannedQuery == null ? null : plannedQuery.getExecutionQuery(), executionQuery);
        compare(fields, "query.contextHints", plannedQuery == null ? List.of() : copy(plannedQuery.getContextHints()), contextHints);
        compare(fields, "scope.mode", plan.getScopeMode(), scopeMode);
        compare(fields, "scope.knowledgeBaseIds", copy(plan.getKnowledgeBaseIds()), knowledgeBaseIds);
        compare(fields, "scope.allowedDocumentIds", copy(plan.getAllowedDocumentScope()), allowedDocumentScope);
        compare(fields, "scope.documentIds", copy(plan.getDocumentScope()), documentScope);
        compare(fields, "scope.taskIds", copy(plan.getTaskScope()), taskScope);
        compare(fields, "filters.documentNameHints", plannedFilters.documentNameHints(), filters.documentNameHints());
        compare(fields, "filters.sectionPathHints", plannedFilters.sectionPathHints(), filters.sectionPathHints());
        compare(fields, "filters.yearHints", plannedFilters.yearHints(), filters.yearHints());
        compare(fields, "filters.entityHints", plannedFilters.entityHints(), filters.entityHints());
        compare(fields, "filters.documentIdHints", plannedFilters.documentIdHints(), filters.documentIdHints());
        compare(fields, "intent.table", plannedTable, tableIntent);
        compare(fields, "intent.graph", plannedGraph, graphIntent);
        compare(fields, "intent.raptor", plannedRaptor, raptorIntent);

        LinkedHashSet<String> channelNames = new LinkedHashSet<>();
        channelNames.addAll(plannedChannels.keySet());
        channelNames.addAll(requestChannels.keySet());
        for (String channelName : channelNames) {
            ChannelSpec planned = plannedChannels.get(channelName);
            ChannelSpec requested = requestChannels.get(channelName);
            compare(fields, channelField(channelName, "enabled"), value(planned, ChannelSpec::enabled), value(requested, ChannelSpec::enabled));
            compare(fields, channelField(channelName, "topK"), value(planned, ChannelSpec::topK), value(requested, ChannelSpec::topK));
            compare(fields, channelField(channelName, "timeoutMs"), value(planned, ChannelSpec::timeoutMs), value(requested, ChannelSpec::timeoutMs));
            compare(fields, channelField(channelName, "budget"), value(planned, ChannelSpec::budget), value(requested, ChannelSpec::budget));
            compare(fields, channelField(channelName, "weight"), value(planned, ChannelSpec::weight), value(requested, ChannelSpec::weight));
            compare(fields, channelField(channelName, "minimumScore"), value(planned, ChannelSpec::minimumScore), value(requested, ChannelSpec::minimumScore));
            compare(fields, channelField(channelName, "relativeScoreFloor"), value(planned, ChannelSpec::relativeScoreFloor), value(requested, ChannelSpec::relativeScoreFloor));
        }

        List<String> differences = fields.entrySet().stream()
            .filter(entry -> !entry.getValue().matches())
            .map(Map.Entry::getKey)
            .toList();
        return new Reconciliation(subQuestionIndex, fields, differences);
    }

    private static RetrievalExecutionQuery requirePlannedQuery(RetrievalPlan plan, int queryIndex) {
        RetrievalExecutionQuery query = findPlannedQuery(plan, queryIndex);
        if (query == null) {
            throw new IllegalArgumentException("Execution query index is absent from RetrievalPlan: " + queryIndex);
        }
        return query;
    }

    private static RetrievalExecutionQuery findPlannedQuery(RetrievalPlan plan, int queryIndex) {
        if (plan.getQuestionPlan() == null || plan.getQuestionPlan().getExecutionQueries() == null) {
            return null;
        }
        List<RetrievalExecutionQuery> matches = plan.getQuestionPlan().getExecutionQueries().stream()
            .filter(Objects::nonNull)
            .filter(query -> query.getIndex() == queryIndex)
            .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean sameQuery(RetrievalExecutionQuery left, RetrievalExecutionQuery right) {
        return left.getIndex() == right.getIndex()
            && Objects.equals(left.getSourceQuestion(), right.getSourceQuestion())
            && Objects.equals(left.getNormalizedQuery(), right.getNormalizedQuery())
            && Objects.equals(left.getExecutionQuery(), right.getExecutionQuery())
            && Objects.equals(copy(left.getContextHints()), copy(right.getContextHints()));
    }

    private static Map<String, ChannelSpec> channelMap(List<ChannelSpec> values) {
        LinkedHashMap<String, ChannelSpec> result = new LinkedHashMap<>();
        for (ChannelSpec value : values) {
            if (value != null) {
                result.put(value.channelName(), value);
            }
        }
        return result;
    }

    private static void compare(Map<String, FieldComparison> fields, String field, Object planned, Object requested) {
        fields.put(field, new FieldComparison(planned, requested, Objects.equals(planned, requested)));
    }

    private static String channelField(String channelName, String field) {
        return "channels." + channelName + "." + field;
    }

    private static <T> Object value(ChannelSpec spec, java.util.function.Function<ChannelSpec, T> extractor) {
        return spec == null ? null : extractor.apply(spec);
    }

    private static void validate(int subQuestionIndex,
                                 String executionQuery,
                                 KnowledgeBaseSelectionMode scopeMode,
                                 List<Long> documentScope,
                                 List<Long> taskScope,
                                 List<ChannelSpec> channels) {
        if (subQuestionIndex <= 0 || executionQuery == null || executionQuery.isBlank()) {
            throw new IllegalArgumentException("Execution request query index and text are required");
        }
        if (scopeMode == null || scopeMode == KnowledgeBaseSelectionMode.NONE) {
            throw new IllegalArgumentException("Execution request scope mode is required");
        }
        if (documentScope.isEmpty() || documentScope.size() != taskScope.size()) {
            throw new IllegalArgumentException("Execution request document and task scope must be non-empty and aligned");
        }
        Set<String> names = new LinkedHashSet<>();
        if (channels.isEmpty() || channels.stream().anyMatch(channel -> channel == null || !names.add(channel.channelName()))) {
            throw new IllegalArgumentException("Execution request channels must be non-empty and unique");
        }
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    public record Filters(
        List<String> documentNameHints,
        List<String> sectionPathHints,
        List<String> yearHints,
        List<String> entityHints,
        List<Long> documentIdHints
    ) {
        public Filters {
            documentNameHints = immutableList(documentNameHints);
            sectionPathHints = immutableList(sectionPathHints);
            yearHints = immutableList(yearHints);
            entityHints = immutableList(entityHints);
            documentIdHints = immutableList(documentIdHints);
        }

        private static Filters from(RetrievalMetadataFilters filters) {
            return filters == null
                ? empty()
                : new Filters(
                    filters.getDocumentNameHints(),
                    filters.getSectionPathHints(),
                    filters.getYearHints(),
                    filters.getEntityHints(),
                    filters.getDocumentIdHints()
                );
        }

        private static Filters empty() {
            return new Filters(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    /**
     * 各通道实际执行的文档范围：授权 documentScope 与已授权 documentIdHints 的交集。
     * hints 为空（未授权/未点名产品）时即原 scope。存储层继续只消费 documentIds 列表，
     * 交集在这里一次算清，向量/关键词/表格/图谱/RAPTOR 全通道一致继承。
     */
    public List<Long> effectiveDocumentScope() {
        List<Long> hints = filters == null ? List.of() : filters.documentIdHints();
        if (hints == null || hints.isEmpty()) {
            return documentScope;
        }
        return documentScope.stream().filter(hints::contains).toList();
    }

    public record ChannelSpec(String channelName,
                              boolean enabled,
                              int topK,
                              long timeoutMs,
                              int budget,
                              double weight,
                              double minimumScore,
                              double relativeScoreFloor) {
        private static ChannelSpec from(RetrievalChannelPlan plan) {
            return new ChannelSpec(
                plan.getChannelName(),
                plan.isEnabled(),
                plan.getTopK(),
                plan.getTimeoutMs(),
                plan.getBudget(),
                plan.getWeight(),
                plan.getMinimumScore(),
                plan.getRelativeScoreFloor()
            );
        }
    }

    public record TableSpec(boolean requested, List<String> tableOps, String source) {
        public TableSpec {
            tableOps = immutableList(tableOps);
        }

        private static TableSpec from(TableIntent intent) {
            return intent == null ? null : new TableSpec(intent.isRequested(), intent.getTableOps(), intent.getSource());
        }
    }

    public record GraphSpec(boolean requested, List<String> entities, List<String> targetEntities, int maxHops, String source) {
        public GraphSpec {
            entities = immutableList(entities);
            targetEntities = immutableList(targetEntities);
        }

        private static GraphSpec from(GraphIntent intent) {
            return intent == null
                ? null
                : new GraphSpec(intent.isRequested(), intent.getEntities(), intent.getTargetEntities(), intent.getMaxHops(), intent.getSource());
        }
    }

    public record RaptorSpec(boolean requested, boolean summaryRequested, int sourceChunkTopK, String source) {
        private static RaptorSpec from(RaptorIntent intent) {
            return intent == null
                ? null
                : new RaptorSpec(intent.isRequested(), intent.isSummaryRequested(), intent.getSourceChunkTopK(), intent.getSource());
        }
    }

    public record FieldComparison(Object plannedValue, Object requestValue, boolean matches) {
    }

    public record Reconciliation(int subQuestionIndex,
                                 Map<String, FieldComparison> fields,
                                 List<String> differences) {
        public Reconciliation {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields == null ? Map.of() : fields));
            differences = immutableList(differences);
        }
    }
}
