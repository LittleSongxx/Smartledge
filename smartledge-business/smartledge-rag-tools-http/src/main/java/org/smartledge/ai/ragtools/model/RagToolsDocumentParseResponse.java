package org.smartledge.ai.ragtools.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RagToolsDocumentParseResponse {

    private String parsedText;

    private Integer charCount;

    private Integer tokenCount;

    private Integer structureLevel;

    private Integer contentQualityLevel;

    private Integer headingCount;

    private Integer paragraphCount;

    private Integer maxParagraphLength;

    private List<Artifact> artifacts;

    private List<Block> blocks;

    private String providerName;

    private String providerVersion;

    private List<String> capabilities;

    private Integer elapsedMs;

    private List<String> warnings;

    private String failedReason;

    private Map<String, Object> traceMetadata;

    private MarkdownSyntax markdownSyntax;

    @Data
    public static class Artifact {

        private String artifactType;

        private String fileName;

        private String contentType;

        private String contentBase64;

        private String contentHash;

        private String parserName;

        private String parserVersion;
    }

    @Data
    public static class Block {

        private Integer blockNo;

        private String blockType;

        private Integer parentBlockNo;

        private String sectionPath;

        private String canonicalPath;

        private Integer pageNo;

        private String pageRange;

        private String bboxJson;

        private String text;

        private String contentWithWeight;

        private String tableHtml;

        private List<List<String>> tableRows;

        private String imageFileName;

        private String imageContentBase64;

        private String imageCaption;

        private String metadataJson;
    }

    @Data
    public static class MarkdownSyntax {

        private String schemaVersion;

        private String sourceOrigin;

        private String sourceText;

        private Integer sourceLengthBytes;

        private String sourceSha256;

        private List<MarkdownSyntaxNode> nodes;
    }

    @Data
    public static class MarkdownSyntaxNode {

        private Integer order;

        private String nodeId;

        private String parentNodeId;

        private String nodeType;

        private String origin;

        private SourceSpan sourceSpan;

        private String text;

        private Integer level;

        private String marker;

        private Integer ordinal;

        private Boolean header;

        private String alignment;

        private Integer rowIndex;

        private Integer columnIndex;

        private String info;
    }

    @Data
    public static class SourceSpan {

        private Integer startByte;

        private Integer endByte;

        private Integer startLine;

        private Integer startColumn;

        private Integer endLine;

        private Integer endColumn;
    }
}
