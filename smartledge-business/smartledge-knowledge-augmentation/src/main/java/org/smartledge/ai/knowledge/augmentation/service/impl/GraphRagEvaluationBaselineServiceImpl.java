package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEvaluationBaselineSuites;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEvaluationBatchReport;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEvaluationReport;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEvaluationSourceBinding;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEvaluationSuite;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagQualityReport;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagSearchResult;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagEvaluationBaselineService;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagEvaluationService;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagSearchService;
import org.smartledge.ai.rag.runtime.model.graph.GraphRagSearchRequest;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentIndexStatusEnum;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class GraphRagEvaluationBaselineServiceImpl implements GraphRagEvaluationBaselineService {

    private final SuperAgentDocumentMapper documentMapper;

    private final GraphRagEvaluationService evaluationService;

    private final GraphRagSearchService graphRagSearchService;

    public GraphRagEvaluationBaselineServiceImpl(SuperAgentDocumentMapper documentMapper,
                                                 GraphRagEvaluationService evaluationService,
                                                 GraphRagSearchService graphRagSearchService) {
        this.documentMapper = documentMapper;
        this.evaluationService = evaluationService;
        this.graphRagSearchService = graphRagSearchService;
    }

    @Override
    @Transactional(readOnly = true)
    public GraphRagEvaluationBatchReport evaluateO6LlmNerBaseline() {
        Map<String, GraphRagEvaluationSourceBinding> bindings = o6LlmNerSourceBindings();
        List<GraphRagEvaluationSuite> suites = GraphRagEvaluationBaselineSuites.o6LlmNerBaselineBySource(bindings);
        return evaluationService.evaluateBatch(
            GraphRagEvaluationBaselineSuites.O6_LLM_NER_BATCH_ID,
            GraphRagEvaluationBaselineSuites.O6_LLM_NER_BATCH_NAME,
            suites
        );
    }

    @Override
    @Transactional(readOnly = true)
    public GraphRagEvaluationBatchReport evaluateO6CrossDocumentBaseline() {
        Map<String, GraphRagEvaluationSourceBinding> bindings = sourceBindings(List.of(
            GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_EVIDENCE,
            GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_ALIAS
        ));
        GraphRagEvaluationReport report = evaluateAuditSystemPermissionCrossDocument(bindings);
        return singleReportBatch(
            GraphRagEvaluationBaselineSuites.O6_CROSS_DOCUMENT_BATCH_ID,
            GraphRagEvaluationBaselineSuites.O6_CROSS_DOCUMENT_BATCH_NAME,
            report
        );
    }

    private Map<String, GraphRagEvaluationSourceBinding> o6LlmNerSourceBindings() {
        return sourceBindings(List.of(
            GraphRagEvaluationBaselineSuites.SOURCE_PRODUCTION_RELEASE,
            GraphRagEvaluationBaselineSuites.SOURCE_CUSTOMER_DATA_ACCESS
        ));
    }

    private Map<String, GraphRagEvaluationSourceBinding> sourceBindings(List<String> sourceDocuments) {
        List<SuperAgentDocument> indexedDocuments = activeIndexedDocuments();
        Map<String, GraphRagEvaluationSourceBinding> result = new LinkedHashMap<>();
        for (String sourceDocument : sourceDocuments) {
            result.put(sourceDocument, bindSource(sourceDocument, indexedDocuments));
        }
        return result;
    }

    private GraphRagEvaluationReport evaluateAuditSystemPermissionCrossDocument(
        Map<String, GraphRagEvaluationSourceBinding> bindings
    ) {
        String suiteId = "o6-crossdoc-audit-system-permission-requirements";
        String name = "审计系统跨文档召回权限要求";
        String question = "审计系统有哪些权限相关要求？";
        GraphRagEvaluationSourceBinding evidenceDoc = bindings.get(GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_EVIDENCE);
        GraphRagEvaluationSourceBinding aliasDoc = bindings.get(GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_ALIAS);
        List<GraphRagEvaluationSourceBinding> requiredBindings = List.of(evidenceDoc, aliasDoc);
        List<String> missingSources = requiredBindings.stream()
            .filter(binding -> binding == null || !Boolean.TRUE.equals(binding.getMatched())
                || binding.getDocumentId() == null || binding.getTaskId() == null)
            .map(binding -> binding == null ? "unknown" : binding.getSourceDocument())
            .toList();
        if (!missingSources.isEmpty()) {
            return crossDocumentReport(
                suiteId,
                name,
                question,
                0D,
                "未找到已完成索引构建的跨文档 O6 样例：" + String.join("，", missingSources),
                null,
                evidenceDoc == null ? null : evidenceDoc.getDocumentId(),
                evidenceDoc == null ? null : evidenceDoc.getTaskId()
            );
        }

        List<Long> documentIds = requiredBindings.stream()
            .map(GraphRagEvaluationSourceBinding::getDocumentId)
            .filter(Objects::nonNull)
            .toList();
        List<Long> taskIds = requiredBindings.stream()
            .map(GraphRagEvaluationSourceBinding::getTaskId)
            .filter(Objects::nonNull)
            .toList();
        List<GraphRagSearchResult> searchResults = graphRagSearchService.search(new GraphRagSearchRequest(
            question,
            question,
            documentIds,
            taskIds,
            8,
            2,
            List.of()
        )).results();
        GraphRagSearchResult matched = searchResults.stream()
            .filter(result -> matchesAuditPermissionCrossDocumentResult(result, evidenceDoc))
            .findFirst()
            .orElse(null);
        if (matched == null) {
            return crossDocumentReport(
                suiteId,
                name,
                question,
                0D,
                "跨文档 GraphRAG 未从“审计系统”别名扩展到 AuditTrail 权限记录证据。",
                null,
                evidenceDoc.getDocumentId(),
                evidenceDoc.getTaskId()
            );
        }
        CrossDocumentReportAssessment reportAssessment = assessCrossDocumentReport(searchResults, matched);
        if (!reportAssessment.passed()) {
            return crossDocumentReport(
                suiteId,
                name,
                question,
                0.78D,
                "跨文档 GraphRAG 已命中关系证据，但 community report 质量未达标：" + reportAssessment.reason(),
                matched,
                evidenceDoc.getDocumentId(),
                evidenceDoc.getTaskId()
            );
        }
        String summary = "跨文档 GraphRAG 已通过 canonical 扩展命中权限记录证据："
            + StrUtil.blankToDefault(matched.getGraphPath(), "未记录图谱路径")
            + "；canonical="
            + StrUtil.blankToDefault(matched.getCanonicalEntityName(), "-")
            + " entities=" + matched.getCanonicalEntityCount()
            + " docs=" + matched.getCanonicalDocumentCount()
            + "；report=" + reportAssessment.reason();
        return crossDocumentReport(
            suiteId,
            name,
            question,
            1D,
            summary,
            matched,
            evidenceDoc.getDocumentId(),
            evidenceDoc.getTaskId()
        );
    }

    private boolean matchesAuditPermissionCrossDocumentResult(GraphRagSearchResult result,
                                                              GraphRagEvaluationSourceBinding evidenceDoc) {
        if (result == null || evidenceDoc == null || !Objects.equals(result.getDocumentId(), evidenceDoc.getDocumentId())) {
            return false;
        }
        if (!"RECORDS".equalsIgnoreCase(StrUtil.blankToDefault(result.getRelationType(), ""))) {
            return false;
        }
        if (result.getCanonicalEntityCount() == null || result.getCanonicalEntityCount() < 2
            || result.getCanonicalDocumentCount() == null || result.getCanonicalDocumentCount() < 2) {
            return false;
        }
        String anchorText = normalizeName(Stream.of(
                result.getEntityName(),
                result.getCanonicalEntityName()
            )
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" ")));
        boolean canonicalAnchoredOnAuditSystem = anchorText.contains("audittrail") || anchorText.contains("审计系统");
        if (!canonicalAnchoredOnAuditSystem) {
            return false;
        }
        String quote = normalizeName(result.getQuoteText());
        String graphPath = normalizeName(result.getGraphPath());
        String relatedText = normalizeName(Stream.of(
                result.getRelatedEntityName(),
                result.getGraphPath()
            )
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" ")));
        boolean hasAuditTrail = quote.contains("audittrail") || graphPath.contains("audittrail") || graphPath.contains("审计系统");
        boolean hasPermissionTarget = relatedText.contains("权限申请") || relatedText.contains("权限审批")
            || relatedText.contains("权限回收") || relatedText.contains("临时权限延长");
        boolean quoteHasPermissionEvidence = quote.contains("权限申请") || quote.contains("权限审批")
            || quote.contains("权限回收") || quote.contains("临时权限延长");
        return hasAuditTrail && hasPermissionTarget && quoteHasPermissionEvidence;
    }

    private CrossDocumentReportAssessment assessCrossDocumentReport(List<GraphRagSearchResult> searchResults,
                                                                    GraphRagSearchResult matched) {
        String communityKey = matched == null ? "" : matched.getCrossDocumentCommunityKey();
        List<GraphRagSearchResult> communityResults = safeStream(searchResults)
            .filter(result -> result != null && StrUtil.isNotBlank(result.getCrossDocumentCommunityKey()))
            .filter(result -> StrUtil.isBlank(communityKey) || communityKey.equals(result.getCrossDocumentCommunityKey()))
            .toList();
        if (communityResults.isEmpty()) {
            return CrossDocumentReportAssessment.failed("缺少跨文档 community 命中。");
        }
        int documentCount = communityResults.stream()
            .map(GraphRagSearchResult::getCrossDocumentCommunityDocumentCount)
            .filter(Objects::nonNull)
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0);
        int evidenceCount = communityResults.stream()
            .map(GraphRagSearchResult::getCrossDocumentCommunityEvidenceCount)
            .filter(Objects::nonNull)
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0);
        int relationGroupCount = communityResults.stream()
            .map(GraphRagSearchResult::getCrossDocumentCommunityRelationGroupCount)
            .filter(Objects::nonNull)
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0);
        if (documentCount < 2) {
            return CrossDocumentReportAssessment.failed("community 覆盖文档数不足 2。");
        }
        if (evidenceCount < 2) {
            return CrossDocumentReportAssessment.failed("community 可追溯证据不足 2 条。");
        }
        if (relationGroupCount < 1) {
            return CrossDocumentReportAssessment.failed("community 缺少关系组覆盖。");
        }
        String reportText = normalizeName(communityResults.stream()
            .flatMap(result -> Stream.of(result.getCommunityTitle(), result.getCommunitySummary()))
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" ")));
        List<String> missingSections = new ArrayList<>();
        if (!reportText.contains("核心实体")) {
            missingSections.add("核心实体");
        }
        if (!reportText.contains("关键关系") && !reportText.contains("关系类型")) {
            missingSections.add("关键关系类型");
        }
        if (!reportText.contains("覆盖范围") && !reportText.contains("覆盖")) {
            missingSections.add("覆盖范围");
        }
        if (!reportText.contains("证据边界") && !reportText.contains("可追溯证据")) {
            missingSections.add("证据边界");
        }
        if (!reportText.contains("不可推断") && !reportText.contains("不能推出")) {
            missingSections.add("不可推断边界");
        }
        if (!missingSections.isEmpty()) {
            return CrossDocumentReportAssessment.failed("report 缺少结构段：" + String.join("、", missingSections));
        }
        return CrossDocumentReportAssessment.passed("docs=" + documentCount
            + " evidence=" + evidenceCount
            + " relationGroups=" + relationGroupCount
            + " sections=核心实体/关键关系/覆盖范围/证据边界/不可推断");
    }

    private GraphRagEvaluationReport crossDocumentReport(String suiteId,
                                                         String name,
                                                         String question,
                                                         double score,
                                                         String summary,
                                                         GraphRagSearchResult matched,
                                                         Long documentId,
                                                         Long taskId) {
        boolean passed = score >= 0.85D;
        GraphRagEvaluationReport.EntityResult entityResult = GraphRagEvaluationReport.EntityResult.builder()
            .expectedName("审计系统/AuditTrail")
            .expectedEntityType("SYSTEM")
            .required(true)
            .matched(passed)
            .actualEntityId(matched == null ? null : matched.getEntityId())
            .actualName(matched == null ? null : firstNonBlank(matched.getCanonicalEntityName(), matched.getEntityName()))
            .reason(passed
                ? "跨文档 canonical entity 命中，entities=" + matched.getCanonicalEntityCount()
                    + " docs=" + matched.getCanonicalDocumentCount()
                : summary)
            .build();
        GraphRagEvaluationReport.RelationResult relationResult = GraphRagEvaluationReport.RelationResult.builder()
            .expectedSourceName("审计系统/AuditTrail")
            .expectedTargetName("权限申请/权限审批/权限回收/临时权限延长")
            .expectedRelationType("RECORDS")
            .required(true)
            .matched(passed)
            .actualRelationId(matched == null ? null : matched.getRelationId())
            .actualSourceEntityId(matched == null ? null : matched.getEntityId())
            .actualSourceName(matched == null ? null : matched.getEntityName())
            .actualTargetEntityId(matched == null ? null : matched.getRelatedEntityId())
            .actualTargetName(matched == null ? null : matched.getRelatedEntityName())
            .reason(summary)
            .build();
        GraphRagEvaluationReport.EvidenceResult evidenceResult = GraphRagEvaluationReport.EvidenceResult.builder()
            .expectedQuoteKeywords(List.of("审计系统", "AuditTrail", "权限申请", "审批", "回收"))
            .expectedSourceName("审计系统/AuditTrail")
            .expectedTargetName("权限申请")
            .expectedRelationType("RECORDS")
            .required(true)
            .matched(passed)
            .actualEvidenceId(matched == null ? null : matched.getEvidenceId())
            .actualEntityId(matched == null ? null : matched.getEntityId())
            .actualRelationId(matched == null ? null : matched.getRelationId())
            .actualChunkId(matched == null ? null : matched.getChunkId())
            .actualQuoteText(matched == null ? null : matched.getQuoteText())
            .actualPageNo(matched == null ? null : matched.getPageNo())
            .actualPageRange(matched == null ? null : matched.getPageRange())
            .actualSectionPath(matched == null ? null : matched.getSectionPath())
            .reason(summary)
            .build();
        return GraphRagEvaluationReport.builder()
            .suiteId(suiteId)
            .name(name)
            .scenario("O1-06B 跨文档实体关系 / O6 跨文档 GraphRAG")
            .question(question)
            .sourceDocument(GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_EVIDENCE
                + " + " + GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_ALIAS)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.85D)
            .passed(passed)
            .evaluationLevel(passed ? GraphRagQualityReport.LEVEL_STRONG : GraphRagQualityReport.LEVEL_WEAK)
            .evaluationScore(score)
            .summary(summary)
            .expectedEntityCount(1L)
            .matchedEntityCount(passed ? 1L : 0L)
            .expectedRelationCount(1L)
            .matchedRelationCount(passed ? 1L : 0L)
            .forbiddenRelationCount(0L)
            .violatedForbiddenRelationCount(0L)
            .expectedEvidenceCount(1L)
            .matchedEvidenceCount(passed ? 1L : 0L)
            .entityRecall(score)
            .relationRecall(score)
            .relationPrecision(1D)
            .evidenceRecall(score)
            .overallRecall(score)
            .entityResults(List.of(entityResult))
            .relationResults(List.of(relationResult))
            .forbiddenRelationResults(List.of())
            .evidenceResults(List.of(evidenceResult))
            .build();
    }

    private GraphRagEvaluationBatchReport singleReportBatch(String batchId,
                                                            String name,
                                                            GraphRagEvaluationReport report) {
        if (report == null) {
            return GraphRagEvaluationBatchReport.empty(batchId, name);
        }
        boolean passed = Boolean.TRUE.equals(report.getPassed());
        double recall = report.getOverallRecall() == null ? 0D : report.getOverallRecall();
        return GraphRagEvaluationBatchReport.builder()
            .batchId(batchId)
            .name(name)
            .evaluationLevel(passed ? GraphRagQualityReport.LEVEL_STRONG : GraphRagQualityReport.LEVEL_WEAK)
            .evaluationScore(recall)
            .summary(passed
                ? "O6 跨文档 GraphRAG baseline 通过。"
                : "O6 跨文档 GraphRAG baseline 未通过：" + StrUtil.blankToDefault(report.getSummary(), "无原因"))
            .suiteCount(1L)
            .passedSuiteCount(passed ? 1L : 0L)
            .failedSuiteCount(passed ? 0L : 1L)
            .expectedEntityCount(report.getExpectedEntityCount())
            .matchedEntityCount(report.getMatchedEntityCount())
            .expectedRelationCount(report.getExpectedRelationCount())
            .matchedRelationCount(report.getMatchedRelationCount())
            .forbiddenRelationCount(report.getForbiddenRelationCount())
            .violatedForbiddenRelationCount(report.getViolatedForbiddenRelationCount())
            .expectedEvidenceCount(report.getExpectedEvidenceCount())
            .matchedEvidenceCount(report.getMatchedEvidenceCount())
            .entityRecall(report.getEntityRecall())
            .relationRecall(report.getRelationRecall())
            .relationPrecision(report.getRelationPrecision())
            .evidenceRecall(recall)
            .overallRecall(recall)
            .passRate(passed ? 1D : 0D)
            .minSuiteRecall(recall)
            .maxSuiteRecall(recall)
            .reports(List.of(report))
            .failedSuites(passed ? List.of() : List.of(GraphRagEvaluationBatchReport.FailedSuite.builder()
                .suiteId(report.getSuiteId())
                .name(report.getName())
                .sourceDocument(report.getSourceDocument())
                .documentId(report.getDocumentId())
                .taskId(report.getTaskId())
                .overallRecall(report.getOverallRecall())
                .evaluationLevel(report.getEvaluationLevel())
                .reason(report.getSummary())
                .build()))
            .build();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private GraphRagEvaluationSourceBinding bindSource(String sourceDocument, List<SuperAgentDocument> indexedDocuments) {
        return indexedDocuments.stream()
            .filter(document -> matchesSource(document, sourceDocument))
            .findFirst()
            .map(document -> GraphRagEvaluationSourceBinding.builder()
                .sourceDocument(sourceDocument)
                .documentId(document.getId())
                .taskId(document.getLastIndexTaskId())
                .documentName(document.getDocumentName())
                .originalFileName(document.getOriginalFileName())
                .matched(true)
                .build())
            .orElseGet(() -> GraphRagEvaluationSourceBinding.missing(
                sourceDocument,
                "未找到已完成索引构建的 O6 样例文档：" + sourceDocument
            ));
    }

    private List<SuperAgentDocument> activeIndexedDocuments() {
        List<SuperAgentDocument> documents = documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .orderByDesc(SuperAgentDocument::getEditTime)
            .orderByDesc(SuperAgentDocument::getId));
        return safeStream(documents)
            .filter(this::activeIndexed)
            .sorted(Comparator
                .comparing(SuperAgentDocument::getEditTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SuperAgentDocument::getId, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private boolean activeIndexed(SuperAgentDocument document) {
        return document != null
            && Objects.equals(document.getStatus(), BusinessStatus.YES.getCode())
            && Objects.equals(document.getIndexStatus(), DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            && document.getLastIndexTaskId() != null;
    }

    private boolean matchesSource(SuperAgentDocument document, String sourceDocument) {
        if (document == null || StrUtil.isBlank(sourceDocument)) {
            return false;
        }
        Set<String> sourceAliases = sourceAliases(sourceDocument);
        return Stream.of(document.getDocumentName(), document.getOriginalFileName())
            .map(this::normalizeName)
            .anyMatch(sourceAliases::contains);
    }

    private Set<String> sourceAliases(String sourceDocument) {
        String normalizedSource = normalizeName(sourceDocument);
        String sourceFileName = normalizeName(fileName(sourceDocument));
        String sourceStem = normalizeName(stripExtension(fileName(sourceDocument)));
        return Stream.of(normalizedSource, sourceFileName, sourceStem)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toSet());
    }

    private String fileName(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private String stripExtension(String name) {
        if (StrUtil.isBlank(name)) {
            return "";
        }
        int index = name.lastIndexOf('.');
        return index <= 0 ? name : name.substring(0, index);
    }

    private String normalizeName(String value) {
        return StrUtil.blankToDefault(value, "")
            .trim()
            .replace('\\', '/')
            .toLowerCase(Locale.ROOT);
    }

    private Stream<SuperAgentDocument> safeStream(List<SuperAgentDocument> documents) {
        return documents == null ? Stream.empty() : documents.stream();
    }

    private <T> Stream<T> safeStream(Collection<T> values) {
        return values == null ? Stream.empty() : values.stream();
    }

    private record CrossDocumentReportAssessment(boolean passed, String reason) {

        private static CrossDocumentReportAssessment passed(String reason) {
            return new CrossDocumentReportAssessment(true, reason);
        }

        private static CrossDocumentReportAssessment failed(String reason) {
            return new CrossDocumentReportAssessment(false, reason);
        }
    }
}
