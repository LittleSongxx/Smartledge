package org.smartledge.ai.chatagent.rag.retrieve.channel;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.chatagent.rag.model.RetrievalExecutionRequest;
import org.smartledge.ai.rag.runtime.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.rag.runtime.model.raptor.RaptorSearchResult;
import org.smartledge.ai.rag.runtime.port.DocumentEvidencePort;
import org.smartledge.ai.rag.runtime.port.RaptorSearchPort;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.enums.RetrievalChannelEnum;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RaptorRetrievalChannel implements RetrievalChannel {

    private static final String SOURCE_TYPE = "RAPTOR";
    private static final String SOURCE_STATUS_SOURCE_CHUNK = "SOURCE_CHUNK";
    private static final String SOURCE_STATUS_SOURCE_PARENT_BLOCK = "SOURCE_PARENT_BLOCK";
    private static final String SOURCE_STATUS_SUMMARY_ONLY = "SUMMARY_ONLY";

    private final RaptorSearchPort raptorSearchService;
    private final DocumentEvidencePort documentKnowledgeService;

    public RaptorRetrievalChannel(RaptorSearchPort raptorSearchService,
                                  DocumentEvidencePort documentKnowledgeService) {
        this.raptorSearchService = raptorSearchService;
        this.documentKnowledgeService = documentKnowledgeService;
    }

    @Override
    public String channelName() {
        return RetrievalChannelEnum.RAPTOR.getName();
    }

    @Override
    public RetrievalChannelResult retrieve(RetrievalExecutionRequest request) {
        RetrievalExecutionRequest.ChannelSpec channel = request.requireChannel(channelName());
        List<RaptorSearchResult> results = raptorSearchService.search(
            request.executionQuery(),
            request.documentScope(),
            request.taskScope(),
            channel.topK(),
            request.raptorIntent().sourceChunkTopK()
        );
        if (results.isEmpty()) {
            return new RetrievalChannelResult(channelName(), List.of());
        }

        Map<Long, KnowledgeDocumentDescriptor> documentDescriptors = resolveDocumentDescriptors(request);
        List<RetrievalDocument> documents = results.stream()
            .map(result -> toDocument(request.executionQuery(), result, documentDescriptors))
            .toList();
        return new RetrievalChannelResult(channelName(), documents);
    }

    private RetrievalDocument toDocument(String subQuestion,
                                RaptorSearchResult result,
                                Map<Long, KnowledgeDocumentDescriptor> documentDescriptors) {
        KnowledgeDocumentDescriptor descriptor = documentDescriptors.get(result.getDocumentId());
        String documentName = StrUtil.blankToDefault(descriptor == null ? null : descriptor.getDocumentName(), "文档摘要树");
        String text = renderEvidenceText(subQuestion, result);
        String sourceStatus = resolveSourceStatus(result);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, SOURCE_TYPE);
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channelName());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, result.getScore());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, result.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, documentName);
        if (descriptor != null) {
            putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, descriptor.getKnowledgeBaseId());
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, StrUtil.blankToDefault(descriptor.getKnowledgeBaseName(), ""));
        }
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, result.getTaskId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, result.getParentBlockId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.CHUNK_ID, result.getChunkId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.CHUNK_NO, result.getChunkNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, StrUtil.blankToDefault(result.getSectionPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, result.getPageNo());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, StrUtil.blankToDefault(result.getPageRange(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, StrUtil.blankToDefault(result.getBboxJson(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, StrUtil.blankToDefault(result.getSourceBlockIds(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, switch (sourceStatus) {
            case SOURCE_STATUS_SOURCE_CHUNK -> "RAPTOR_SOURCE_CHUNK";
            case SOURCE_STATUS_SOURCE_PARENT_BLOCK -> "RAPTOR_PARENT_CONTEXT";
            default -> "RAPTOR_SUMMARY";
        });
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, StrUtil.blankToDefault(result.getTitle(), result.getRaptorNodeTitle()));
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET,
            SOURCE_STATUS_SOURCE_CHUNK.equals(sourceStatus)
                ? StrUtil.blankToDefault(result.getChunkText(), "")
                : StrUtil.blankToDefault(result.getRaptorSummary(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, result.getRaptorNodeId());
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_TITLE, StrUtil.blankToDefault(result.getRaptorNodeTitle(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_LEVEL, result.getRaptorNodeLevel());
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_SUMMARY, StrUtil.blankToDefault(result.getRaptorSummary(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS, sourceStatus);
        // RAPTOR 内部弱词面 boost 作为 rank feature 观测显式暴露给 O9（P11），仅供展示，不反向影响排序/引用。
        if (result.getRankFeatureBoost() != null && result.getRankFeatureBoost() > 0D) {
            metadata.put(DocumentKnowledgeMetadataKeys.RANK_FEATURE,
                String.format("raptorLexicalBoost=%.4f", result.getRankFeatureBoost()));
        }

        return RetrievalDocument.builder()
            .id(raptorDocumentId(result))
            .text(text)
            .metadata(metadata)
            .score(result.getScore())
            .build();
    }

    private String renderEvidenceText(String subQuestion, RaptorSearchResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("[RAPTOR 层级摘要检索]\n");
        builder.append("用户问题：").append(StrUtil.blankToDefault(subQuestion, "")).append('\n');
        builder.append("命中摘要：").append(StrUtil.blankToDefault(result.getRaptorNodeTitle(), "层级摘要")).append('\n');
        builder.append("摘要层级：").append(result.getRaptorNodeLevel() == null ? "" : result.getRaptorNodeLevel()).append('\n');
        builder.append("摘要内容：").append(StrUtil.blankToDefault(result.getRaptorSummary(), "")).append('\n');
        if (StrUtil.isNotBlank(result.getSectionPath())) {
            builder.append("原文章节：").append(result.getSectionPath()).append('\n');
        }
        if (result.getPageNo() != null) {
            builder.append("原文页码：").append(result.getPageNo()).append('\n');
        }
        String sourceStatus = resolveSourceStatus(result);
        if (SOURCE_STATUS_SUMMARY_ONLY.equals(sourceStatus)) {
            builder.append("种类：综述。未下钻到 source chunk 或 ParentBlock，按 SUMMARY 引用，不要写成原文摘录。\n");
        }
        else if (SOURCE_STATUS_SOURCE_PARENT_BLOCK.equals(sourceStatus) && StrUtil.isBlank(result.getChunkText())) {
            builder.append("下钻状态：已定位到 ParentBlock，但当前结果未携带 chunk 原文。\n");
        }
        if (StrUtil.isNotBlank(result.getChunkText())) {
            builder.append("下钻原文：").append(result.getChunkText()).append('\n');
        }
        return builder.toString().trim();
    }

    private String resolveSourceStatus(RaptorSearchResult result) {
        String sourceStatus = StrUtil.blankToDefault(result.getSourceStatus(), "").trim().toUpperCase();
        if (SOURCE_STATUS_SOURCE_CHUNK.equals(sourceStatus)) {
            if (result.getDocumentId() != null
                && result.getChunkId() != null
                && StrUtil.isNotBlank(result.getChunkText())) {
                return SOURCE_STATUS_SOURCE_CHUNK;
            }
            return result.getParentBlockId() == null
                ? SOURCE_STATUS_SUMMARY_ONLY
                : SOURCE_STATUS_SOURCE_PARENT_BLOCK;
        }
        if (SOURCE_STATUS_SOURCE_PARENT_BLOCK.equals(sourceStatus)) {
            return SOURCE_STATUS_SOURCE_PARENT_BLOCK;
        }
        if (SOURCE_STATUS_SUMMARY_ONLY.equals(sourceStatus)) {
            return SOURCE_STATUS_SUMMARY_ONLY;
        }
        if (result.getDocumentId() != null
            && result.getChunkId() != null
            && StrUtil.isNotBlank(result.getChunkText())) {
            return SOURCE_STATUS_SOURCE_CHUNK;
        }
        if (result.getParentBlockId() != null) {
            return SOURCE_STATUS_SOURCE_PARENT_BLOCK;
        }
        return SOURCE_STATUS_SUMMARY_ONLY;
    }

    private String raptorDocumentId(RaptorSearchResult result) {
        if (result.getChunkId() != null) {
            return "raptor-" + result.getRaptorNodeId() + "-" + result.getChunkId();
        }
        if (result.getParentBlockId() != null) {
            return "raptor-" + result.getRaptorNodeId() + "-parent-" + result.getParentBlockId();
        }
        return "raptor-" + result.getRaptorNodeId() + "-summary";
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
}
