package org.smartledge.ai.manage.model;

import lombok.Builder;
import lombok.Data;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构锚点正文证据扩展请求。只使用结构化 metadata，不使用业务关键词。
 */
@Data
@Builder
public class StructureAnchoredEvidenceRequest {

    @Builder.Default
    private List<RetrievalDocument> candidateDocuments = new ArrayList<>();

    @Builder.Default
    private List<String> sectionAnchors = new ArrayList<>();

    @Builder.Default
    private List<Long> structureNodeIds = new ArrayList<>();

    @Builder.Default
    private List<Long> sourceHeadingNodeIds = new ArrayList<>();

    @Builder.Default
    private List<String> canonicalPaths = new ArrayList<>();

    @Builder.Default
    private List<Long> documentIds = new ArrayList<>();

    @Builder.Default
    private List<Long> taskIds = new ArrayList<>();

    @Builder.Default
    private List<Long> knowledgeBaseIds = new ArrayList<>();

    private int maxPerAnchor;

    private int maxTotal;

    private int maxChars;
}
