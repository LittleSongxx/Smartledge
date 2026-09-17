package org.smartledge.ai.manage.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.smartledge.ai.manage.dto.DocumentIndexBuildDto;
import org.smartledge.ai.manage.dto.DocumentIndexBuildProgressQueryDto;
import org.smartledge.ai.manage.dto.DocumentChunkQueryDto;
import org.smartledge.ai.manage.dto.DocumentChunkDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentDeleteDto;
import org.smartledge.ai.manage.dto.DocumentPageQueryDto;
import org.smartledge.ai.manage.dto.DocumentParseArtifactContentQueryDto;
import org.smartledge.ai.manage.dto.DocumentParseArtifactQueryDto;
import org.smartledge.ai.manage.dto.DocumentParseRouteProgressQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagPageOverlayDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagParseWorkbenchQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagSnapshotQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactNodeDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactNodePageQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactGraphWindowQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactRelationPageQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactTableWindowQueryDto;
import org.smartledge.ai.manage.dto.DocumentStrategyConfirmDto;
import org.smartledge.ai.manage.dto.DocumentStrategyPlanQueryDto;
import org.smartledge.ai.manage.dto.DocumentTaskLogQueryDto;
import org.smartledge.ai.manage.dto.DocumentUploadDto;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagEvaluationBatchReport;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagEvaluationBaselineService;
import org.smartledge.ai.manage.service.DocumentManageService;
import org.smartledge.ai.manage.support.ChunkingProfileException;
import org.smartledge.ai.manage.service.DocumentRagSnapshotService;
import org.smartledge.ai.manage.service.DocumentRagArtifactService;
import org.smartledge.ai.manage.vo.DocumentIndexBuildVo;
import org.smartledge.ai.manage.vo.DocumentIndexBuildProgressVo;
import org.smartledge.ai.manage.vo.DocumentChunkQueryVo;
import org.smartledge.ai.manage.vo.DocumentChunkDetailVo;
import org.smartledge.ai.manage.vo.DocumentListItemVo;
import org.smartledge.ai.manage.vo.DocumentDeleteVo;
import org.smartledge.ai.manage.vo.DocumentPageQueryVo;
import org.smartledge.ai.manage.vo.DocumentParseArtifactContentVo;
import org.smartledge.ai.manage.vo.DocumentParseArtifactDownloadVo;
import org.smartledge.ai.manage.vo.DocumentParseArtifactListVo;
import org.smartledge.ai.manage.vo.DocumentParseRouteProgressVo;
import org.smartledge.ai.manage.vo.DocumentRagPageOverlayDetailVo;
import org.smartledge.ai.manage.vo.DocumentRagPageOverlayIndexVo;
import org.smartledge.ai.manage.vo.DocumentRagParserDiagnosticVo;
import org.smartledge.ai.manage.vo.DocumentRagSnapshotVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactNodeDetailVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactNodePageVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactGraphWindowVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactRelationPageVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactTableWindowVo;
import org.smartledge.ai.manage.vo.DocumentStrategyConfirmVo;
import org.smartledge.ai.manage.vo.DocumentStrategyPlanQueryVo;
import org.smartledge.ai.manage.vo.DocumentTaskLogQueryVo;
import org.smartledge.ai.manage.vo.DocumentUploadVo;
import org.smartledge.common.ApiResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import org.smartledge.ai.auth.support.RequiresPermission;

/**
 * @description: 控制层
 * @author: Song
 **/

@RestController
@RequestMapping("/manage/document")
@RequiresPermission("document:read")
public class DocumentManageController {

    private final DocumentManageService documentManageService;

    private final DocumentRagSnapshotService documentRagSnapshotService;

    private final DocumentRagArtifactService documentRagArtifactService;

    private final GraphRagEvaluationBaselineService graphRagEvaluationBaselineService;

    public DocumentManageController(DocumentManageService documentManageService,
                                    DocumentRagSnapshotService documentRagSnapshotService,
                                    DocumentRagArtifactService documentRagArtifactService,
                                    GraphRagEvaluationBaselineService graphRagEvaluationBaselineService) {
        this.documentManageService = documentManageService;
        this.documentRagSnapshotService = documentRagSnapshotService;
        this.documentRagArtifactService = documentRagArtifactService;
        this.graphRagEvaluationBaselineService = graphRagEvaluationBaselineService;
    }

    @Operation(summary = "上传文档并投递解析任务")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("document:upload")
    public ApiResponse<DocumentUploadVo> upload(@RequestPart("file") MultipartFile file,
                                                @Valid @RequestPart(value = "meta", required = false) DocumentUploadDto dto) {

        return ApiResponse.ok(documentManageService.upload(file, dto == null ? new DocumentUploadDto() : dto));
    }

    @Operation(summary = "分页查询文档列表")
    @PostMapping("/page/query")
    public ApiResponse<DocumentPageQueryVo> queryDocumentPage(@Valid @RequestBody DocumentPageQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentPage(dto));
    }

    @Operation(summary = "查询文档详情")
    @PostMapping("/detail/query")
    public ApiResponse<DocumentListItemVo> queryDocumentDetail(@Valid @RequestBody DocumentDetailQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentDetail(dto));
    }

    @Operation(summary = "删除文档及其关联数据")
    @PostMapping("/delete")
    @RequiresPermission("document:delete")
    public ApiResponse<DocumentDeleteVo> deleteDocument(@Valid @RequestBody DocumentDeleteDto dto) {
        return ApiResponse.ok(documentManageService.deleteDocument(dto));
    }

    @Operation(summary = "查询文档策略推荐结果")
    @PostMapping("/strategy/plan/query")
    public ApiResponse<DocumentStrategyPlanQueryVo> queryStrategyPlan(@Valid @RequestBody DocumentStrategyPlanQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryStrategyPlan(dto));
    }

    @Operation(summary = "确认文档策略方案")
    @PostMapping("/strategy/confirm")
    @RequiresPermission("document:write")
    public ApiResponse<DocumentStrategyConfirmVo> confirmStrategy(@Valid @RequestBody DocumentStrategyConfirmDto dto) {
        return ApiResponse.ok(documentManageService.confirmStrategy(dto));
    }

    @ExceptionHandler(ChunkingProfileException.class)
    public ResponseEntity<ApiResponse<Void>> chunkingProfileException(ChunkingProfileException exception) {
        String message = switch (exception.getCode()) {
            case "PROFILE_CONFIRMATION_REQUIRED" -> "切块画像或方案版本缺失、过期，请刷新页面后重新确认。";
            case "PROFILE_PARSE_REVISION_STALE" -> "解析版本已变化，请刷新策略方案后重新确认。";
            case "PROFILE_STRATEGY_INCOMPATIBLE" -> "问答对画像仅支持父块结构切分与子块递归切分，请调整策略或改选通用画像。";
            case "PROFILE_NOT_APPLICABLE" -> "当前解析结果不支持所选切块画像，请重新选择。";
            case "PROFILE_NOT_CONFIRMED" -> "切块画像尚未确认，请先确认策略方案。";
            case "PROFILE_REPARSE_REQUIRED" -> "当前方案缺少切块画像，请重新解析文档后确认。";
            case "PROFILE_UNIT_TOO_LARGE" -> "问答单元超过切块上限，请调整文档或改选通用画像。";
            default -> "切块画像校验失败，请检查解析结果并刷新策略方案。";
        };
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message + "（" + exception.getCode() + "）"));
    }

    @Operation(summary = "执行文档索引构建")
    @PostMapping("/index/build")
    @RequiresPermission("document:write")
    public ApiResponse<DocumentIndexBuildVo> buildIndex(@Valid @RequestBody DocumentIndexBuildDto dto) {
        return ApiResponse.ok(documentManageService.buildIndex(dto));
    }

    @Operation(summary = "查询文档索引构建轻量进度")
    @PostMapping("/index/build/progress/query")
    public ApiResponse<DocumentIndexBuildProgressVo> queryIndexBuildProgress(@Valid @RequestBody DocumentIndexBuildProgressQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryIndexBuildProgress(dto));
    }

    @Operation(summary = "查询文档解析与策略推荐轻量进度")
    @PostMapping("/parse-route/progress/query")
    public ApiResponse<DocumentParseRouteProgressVo> queryParseRouteProgress(@Valid @RequestBody DocumentParseRouteProgressQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryParseRouteProgress(dto));
    }

    @Operation(summary = "查询文档解析产物列表")
    @PostMapping("/parse-artifact/query")
    public ApiResponse<DocumentParseArtifactListVo> queryParseArtifacts(@Valid @RequestBody DocumentParseArtifactQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryParseArtifacts(dto));
    }

    @Operation(summary = "查询文档解析产物文本内容")
    @PostMapping("/parse-artifact/content/query")
    public ApiResponse<DocumentParseArtifactContentVo> queryParseArtifactContent(@Valid @RequestBody DocumentParseArtifactContentQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryParseArtifactContent(dto));
    }

    @Operation(summary = "下载文档解析产物")
    @PostMapping("/parse-artifact/download")
    public ResponseEntity<byte[]> downloadParseArtifact(@Valid @RequestBody DocumentParseArtifactContentQueryDto dto) {
        DocumentParseArtifactDownloadVo download = documentManageService.downloadParseArtifact(dto);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(download.getContentType()))
            .contentLength(download.getSize() == null ? 0L : download.getSize())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(download.getFileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
            .body(download.getBytes());
    }

    @Operation(summary = "查询文档 chunk 列表")
    @PostMapping("/chunk/query")
    public ApiResponse<DocumentChunkQueryVo> queryDocumentChunks(@Valid @RequestBody DocumentChunkQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentChunks(dto));
    }

    @Operation(summary = "查询单个文档 chunk 详情")
    @PostMapping("/chunk/detail/query")
    public ApiResponse<DocumentChunkDetailVo> queryDocumentChunkDetail(@Valid @RequestBody DocumentChunkDetailQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentChunkDetail(dto));
    }

    @Operation(summary = "查询文档 RAG 学习快照")
    @PostMapping("/rag/snapshot/query")
    public ApiResponse<DocumentRagSnapshotVo> queryDocumentRagSnapshot(@Valid @RequestBody DocumentRagSnapshotQueryDto dto) {
        return ApiResponse.ok(documentRagSnapshotService.querySnapshot(dto));
    }

    @Operation(summary = "查询文档解析诊断")
    @PostMapping("/rag/parser-diagnostic/query")
    public ApiResponse<DocumentRagParserDiagnosticVo> queryDocumentRagParserDiagnostic(
        @Valid @RequestBody DocumentRagParseWorkbenchQueryDto dto) {
        return ApiResponse.ok(documentRagSnapshotService.queryParserDiagnostic(dto));
    }

    @Operation(summary = "查询文档页面定位索引")
    @PostMapping("/rag/page-overlay/index/query")
    public ApiResponse<DocumentRagPageOverlayIndexVo> queryDocumentRagPageOverlayIndex(
        @Valid @RequestBody DocumentRagParseWorkbenchQueryDto dto) {
        return ApiResponse.ok(documentRagSnapshotService.queryPageOverlayIndex(dto));
    }

    @Operation(summary = "查询文档单页定位详情")
    @PostMapping("/rag/page-overlay/detail/query")
    public ApiResponse<DocumentRagPageOverlayDetailVo> queryDocumentRagPageOverlayDetail(
        @Valid @RequestBody DocumentRagPageOverlayDetailQueryDto dto) {
        return ApiResponse.ok(documentRagSnapshotService.queryPageOverlayDetail(dto));
    }

    @Operation(summary = "分页查询文档 RAG 产物节点")
    @PostMapping("/rag/artifact/node/page/query")
    public ApiResponse<DocumentRagArtifactNodePageVo> queryDocumentRagArtifactNodePage(
        @Valid @RequestBody DocumentRagArtifactNodePageQueryDto dto) {
        return ApiResponse.ok(documentRagArtifactService.queryNodePage(dto));
    }

    @Operation(summary = "查询文档 RAG 产物节点详情")
    @PostMapping("/rag/artifact/node/detail/query")
    public ApiResponse<DocumentRagArtifactNodeDetailVo> queryDocumentRagArtifactNodeDetail(
        @Valid @RequestBody DocumentRagArtifactNodeDetailQueryDto dto) {
        return ApiResponse.ok(documentRagArtifactService.queryNodeDetail(dto));
    }

    @Operation(summary = "查询文档 GraphRAG 有界全景窗口")
    @PostMapping("/rag/artifact/graph/window/query")
    public ApiResponse<DocumentRagArtifactGraphWindowVo> queryDocumentRagArtifactGraphWindow(
        @Valid @RequestBody DocumentRagArtifactGraphWindowQueryDto dto) {
        return ApiResponse.ok(documentRagArtifactService.queryGraphWindow(dto));
    }

    @Operation(summary = "分页查询文档 RAG 产物相邻关系")
    @PostMapping("/rag/artifact/relation/page/query")
    public ApiResponse<DocumentRagArtifactRelationPageVo> queryDocumentRagArtifactRelationPage(
        @Valid @RequestBody DocumentRagArtifactRelationPageQueryDto dto) {
        return ApiResponse.ok(documentRagArtifactService.queryRelationPage(dto));
    }

    @Operation(summary = "分页查询文档 RAG 表格行列窗口")
    @PostMapping("/rag/artifact/table/window/query")
    public ApiResponse<DocumentRagArtifactTableWindowVo> queryDocumentRagArtifactTableWindow(
        @Valid @RequestBody DocumentRagArtifactTableWindowQueryDto dto) {
        return ApiResponse.ok(documentRagArtifactService.queryTableWindow(dto));
    }

    @Operation(summary = "执行 O6 GraphRAG LLM/NER 真实文档 baseline 评测")
    @PostMapping("/graph-rag/evaluation/o6/llm-ner/run")
    @RequiresPermission("observe:read")
    public ApiResponse<GraphRagEvaluationBatchReport> runO6GraphRagLlmNerBaseline() {
        return ApiResponse.ok(graphRagEvaluationBaselineService.evaluateO6LlmNerBaseline());
    }

    @Operation(summary = "执行 O6 GraphRAG 跨文档图谱 baseline 评测")
    @PostMapping("/graph-rag/evaluation/o6/cross-document/run")
    @RequiresPermission("observe:read")
    public ApiResponse<GraphRagEvaluationBatchReport> runO6GraphRagCrossDocumentBaseline() {
        return ApiResponse.ok(graphRagEvaluationBaselineService.evaluateO6CrossDocumentBaseline());
    }

    @Operation(summary = "查询任务执行日志")
    @PostMapping("/task/log/query")
    public ApiResponse<DocumentTaskLogQueryVo> queryTaskLogs(@Valid @RequestBody DocumentTaskLogQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryTaskLogs(dto));
    }

}
