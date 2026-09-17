package org.smartledge.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @description: 支撑组件
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAnalysisResult {

    private String parsedText;

    private Integer charCount;

    private Integer tokenCount;

    private Integer structureLevel;

    private Integer contentQualityLevel;

    private Integer headingCount;

    private Integer paragraphCount;

    private Integer maxParagraphLength;

    private String parserProviderName;

    private String parserProviderVersion;

    private List<String> parserCapabilities = new ArrayList<>();

    private Integer parserElapsedMs;

    private List<String> parserWarnings = new ArrayList<>();

    private String parserFailedReason;

    private Map<String, Object> parserTraceMetadata = new LinkedHashMap<>();

    private DocumentMarkdownSyntaxCandidate markdownSyntax;

    private List<DocumentStructureNodeCandidate> structureNodes = new ArrayList<>();

    private List<DocumentTableCandidate> tableCandidates = new ArrayList<>();

    private List<DocumentParseArtifactCandidate> parseArtifacts = new ArrayList<>();

    private List<DocumentBlockCandidate> blocks = new ArrayList<>();
}
