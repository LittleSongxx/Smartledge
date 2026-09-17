package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.AllArgsConstructor;
import org.smartledge.ai.manage.service.DocumentParserService;
import org.smartledge.ai.manage.support.DocumentAnalysisResult;
import org.smartledge.ai.manage.support.DocumentBlockCandidate;
import org.smartledge.ai.manage.support.DocumentMarkdownSourceSpanCandidate;
import org.smartledge.ai.manage.support.DocumentMarkdownSyntaxContract;
import org.smartledge.ai.manage.support.DocumentMarkdownSyntaxCandidate;
import org.smartledge.ai.manage.support.DocumentMarkdownSyntaxNodeCandidate;
import org.smartledge.ai.manage.support.DocumentParseArtifactCandidate;
import org.smartledge.ai.manage.support.DocumentStructureProjector;
import org.smartledge.ai.manage.support.DocumentTableCandidate;
import org.smartledge.ai.manage.support.DocumentTableProjector;
import org.smartledge.ai.ragtools.client.RagToolsClient;
import org.smartledge.ai.ragtools.model.RagToolsDocumentParseRequest;
import org.smartledge.ai.ragtools.model.RagToolsDocumentParseResponse;
import org.smartledge.enums.DocumentFileTypeEnum;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@AllArgsConstructor
@Service
public class RagToolsDocumentParserService implements DocumentParserService {

    private final RagToolsClient ragToolsClient;

    private final DocumentMarkdownSyntaxContract markdownSyntaxContract;

    private final DocumentStructureProjector structureProjector;

    private final DocumentTableProjector tableProjector;

    @Override
    public DocumentAnalysisResult parse(byte[] bytes,
                                        String originalFileName,
                                        String mimeType,
                                        DocumentFileTypeEnum fileType) {
        RagToolsDocumentParseResponse response = ragToolsClient.parseDocument(new RagToolsDocumentParseRequest(
            originalFileName,
            mimeType,
            fileType == null ? "" : fileType.name(),
            Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes)
        ));
        if (response == null || StrUtil.isBlank(response.getParsedText())) {
            throw new IllegalStateException("rag-tools document parse 返回结果为空");
        }

        List<DocumentBlockCandidate> blocks = toBlocks(response.getBlocks());
        DocumentMarkdownSyntaxCandidate markdownSyntax = toMarkdownSyntax(response.getMarkdownSyntax());
        if (fileType == DocumentFileTypeEnum.MD && markdownSyntax == null) {
            throw new IllegalStateException("rag-tools Markdown 解析结果缺少 markdown-syntax.v1");
        }

        DocumentAnalysisResult result = new DocumentAnalysisResult();
        result.setParsedText(response.getParsedText());
        result.setCharCount(response.getCharCount() == null ? response.getParsedText().length() : response.getCharCount());
        result.setTokenCount(response.getTokenCount() == null ? 0 : response.getTokenCount());
        result.setStructureLevel(response.getStructureLevel() == null ? 0 : response.getStructureLevel());
        result.setContentQualityLevel(response.getContentQualityLevel() == null ? 0 : response.getContentQualityLevel());
        result.setHeadingCount(response.getHeadingCount() == null ? 0 : response.getHeadingCount());
        result.setParagraphCount(response.getParagraphCount() == null ? 0 : response.getParagraphCount());
        result.setMaxParagraphLength(response.getMaxParagraphLength() == null ? 0 : response.getMaxParagraphLength());
        result.setParserProviderName(response.getProviderName());
        result.setParserProviderVersion(response.getProviderVersion());
        result.setParserCapabilities(response.getCapabilities() == null ? List.of() : response.getCapabilities());
        result.setParserElapsedMs(response.getElapsedMs() == null ? 0 : response.getElapsedMs());
        result.setParserWarnings(response.getWarnings() == null ? List.of() : response.getWarnings());
        result.setParserFailedReason(response.getFailedReason());
        Map<String, Object> parserTrace = response.getTraceMetadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(response.getTraceMetadata());
        result.setParserTraceMetadata(parserTrace);
        result.setMarkdownSyntax(markdownSyntax);
        boolean markdownFile = fileType == DocumentFileTypeEnum.MD;
        var structureNodes = markdownFile
            ? structureProjector.projectMarkdown(originalFileName, markdownSyntax)
            : structureProjector.projectProviderBlocks(originalFileName, blocks);
        result.setStructureNodes(structureNodes);
        List<DocumentTableCandidate> tableCandidates = markdownFile
            ? tableProjector.projectMarkdown(markdownSyntax, blocks)
            : tableProjector.projectProviderBlocks(blocks);
        result.setTableCandidates(tableCandidates);
        parserTrace.put("structureProjectionOwner", markdownFile
            ? "JAVA_MARKDOWN_SYNTAX_V1"
            : "JAVA_PROVIDER_BLOCKS");
        parserTrace.put("structureProjectionNodeCount", structureNodes.size());
        parserTrace.put("structureProjectionTraceableNodeCount", structureNodes.stream()
            .filter(node -> node.getSyntaxProvenance() != null)
            .count());
        parserTrace.put("tableProjectionOwner", markdownFile
            ? DocumentTableProjector.MARKDOWN_PROJECTION_OWNER
            : DocumentTableProjector.PROVIDER_PROJECTION_OWNER);
        parserTrace.put("tableProjectionTableCount", tableCandidates.size());
        parserTrace.put("tableProjectionRowCount", tableCandidates.stream()
            .mapToInt(table -> table.rows().size())
            .sum());
        parserTrace.put("tableProjectionCellCount", tableCandidates.stream()
            .flatMap(table -> table.rows().stream())
            .mapToInt(row -> row.cells().size())
            .sum());
        if (markdownSyntax != null) {
            parserTrace.put("structureProjectionSchemaVersion", markdownSyntax.getSchemaVersion());
            parserTrace.put("structureProjectionSourceSha256", markdownSyntax.getSourceSha256());
        }
        result.setParseArtifacts(toArtifacts(
            response.getArtifacts(),
            markdownSyntax,
            originalFileName,
            response.getProviderName(),
            response.getProviderVersion()
        ));
        result.setBlocks(blocks);
        return result;
    }

    private List<DocumentParseArtifactCandidate> toArtifacts(List<RagToolsDocumentParseResponse.Artifact> artifacts,
                                                              DocumentMarkdownSyntaxCandidate markdownSyntax,
                                                              String originalFileName,
                                                              String providerName,
                                                              String providerVersion) {
        List<DocumentParseArtifactCandidate> candidates = new java.util.ArrayList<>();
        for (RagToolsDocumentParseResponse.Artifact artifact : artifacts == null ? List.<RagToolsDocumentParseResponse.Artifact>of() : artifacts) {
            candidates.add(new DocumentParseArtifactCandidate(
                artifact.getArtifactType(),
                artifact.getFileName(),
                artifact.getContentType(),
                artifact.getContentBase64(),
                artifact.getContentHash(),
                artifact.getParserName(),
                artifact.getParserVersion()
            ));
        }
        if (markdownSyntax != null) {
            byte[] bytes = markdownSyntaxContract.write(markdownSyntax);
            candidates.add(new DocumentParseArtifactCandidate(
                "MARKDOWN_SYNTAX_JSON",
                markdownSyntaxFileName(originalFileName),
                "application/json;charset=UTF-8",
                Base64.getEncoder().encodeToString(bytes),
                DigestUtil.sha256Hex(bytes),
                providerName,
                providerVersion
            ));
        }
        return List.copyOf(candidates);
    }

    private String markdownSyntaxFileName(String originalFileName) {
        String fileName = StrUtil.blankToDefault(originalFileName, "document");
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        return baseName + ".markdown-syntax.json";
    }

    private List<DocumentBlockCandidate> toBlocks(List<RagToolsDocumentParseResponse.Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        return blocks.stream()
            .map(block -> new DocumentBlockCandidate(
                block.getBlockNo(),
                block.getBlockType(),
                block.getParentBlockNo(),
                block.getSectionPath(),
                block.getCanonicalPath(),
                block.getPageNo(),
                block.getPageRange(),
                block.getBboxJson(),
                block.getText(),
                block.getContentWithWeight(),
                block.getTableHtml(),
                block.getTableRows() == null ? List.of() : block.getTableRows(),
                block.getImageFileName(),
                block.getImageContentBase64(),
                block.getImageCaption(),
                block.getMetadataJson()
            ))
            .toList();
    }

    private DocumentMarkdownSyntaxCandidate toMarkdownSyntax(RagToolsDocumentParseResponse.MarkdownSyntax syntax) {
        if (syntax == null) {
            return null;
        }
        List<DocumentMarkdownSyntaxNodeCandidate> nodes = syntax.getNodes() == null
            ? null
            : syntax.getNodes().stream().map(this::mapNode).toList();
        return markdownSyntaxContract.validate(new DocumentMarkdownSyntaxCandidate(
            syntax.getSchemaVersion(),
            syntax.getSourceOrigin(),
            syntax.getSourceText(),
            syntax.getSourceLengthBytes(),
            syntax.getSourceSha256(),
            nodes
        ));
    }

    private DocumentMarkdownSyntaxNodeCandidate mapNode(RagToolsDocumentParseResponse.MarkdownSyntaxNode node) {
        if (node == null) {
            return null;
        }
        RagToolsDocumentParseResponse.SourceSpan span = node.getSourceSpan();
        return new DocumentMarkdownSyntaxNodeCandidate(
            node.getOrder(),
            node.getNodeId(),
            node.getParentNodeId(),
            node.getNodeType(),
            node.getOrigin(),
            span == null ? null : new DocumentMarkdownSourceSpanCandidate(
                span.getStartByte(), span.getEndByte(), span.getStartLine(), span.getStartColumn(),
                span.getEndLine(), span.getEndColumn()),
            node.getText(),
            node.getLevel(),
            node.getMarker(),
            node.getOrdinal(),
            node.getHeader(),
            node.getAlignment(),
            node.getRowIndex(),
            node.getColumnIndex(),
            node.getInfo()
        );
    }
}
