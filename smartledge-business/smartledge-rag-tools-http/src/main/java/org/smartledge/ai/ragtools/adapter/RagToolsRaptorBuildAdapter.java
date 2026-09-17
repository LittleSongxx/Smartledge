package org.smartledge.ai.ragtools.adapter;

import org.smartledge.ai.knowledge.augmentation.model.RaptorBuildRequest;
import org.smartledge.ai.knowledge.augmentation.model.RaptorBuildResponse;
import org.smartledge.ai.knowledge.augmentation.port.RaptorBuildPort;
import org.smartledge.ai.ragtools.client.RagToolsClient;
import org.smartledge.ai.ragtools.model.RagToolsRaptorBuildRequest;
import org.smartledge.ai.ragtools.model.RagToolsRaptorBuildResponse;
import org.springframework.stereotype.Component;

/** Maps the augmentation-owned RAPTOR seam to the Python HTTP protocol. */
@Component
public final class RagToolsRaptorBuildAdapter implements RaptorBuildPort {

    private final RagToolsClient client;

    public RagToolsRaptorBuildAdapter(RagToolsClient client) {
        this.client = client;
    }

    @Override
    public RaptorBuildResponse build(RaptorBuildRequest request) {
        RagToolsRaptorBuildResponse response = client.buildRaptor(toTransport(request));
        return response == null ? null : fromTransport(response);
    }

    private RagToolsRaptorBuildRequest toTransport(RaptorBuildRequest request) {
        if (request == null) {
            return null;
        }
        RagToolsRaptorBuildRequest result = new RagToolsRaptorBuildRequest();
        result.setDocumentId(request.getDocumentId()); result.setTaskId(request.getTaskId());
        result.setMaxClusterSize(request.getMaxClusterSize()); result.setMaxLevels(request.getMaxLevels());
        result.setLlmSummaryEnabled(request.getLlmSummaryEnabled());
        result.setLlmConcurrency(request.getLlmConcurrency());
        result.setChunks(request.getChunks() == null ? null : request.getChunks().stream().map(chunk -> {
            RagToolsRaptorBuildRequest.Chunk mapped = new RagToolsRaptorBuildRequest.Chunk();
            mapped.setChunkId(chunk.getChunkId()); mapped.setParentBlockId(chunk.getParentBlockId()); mapped.setChunkNo(chunk.getChunkNo());
            mapped.setChunkType(chunk.getChunkType()); mapped.setTitle(chunk.getTitle()); mapped.setSectionPath(chunk.getSectionPath());
            mapped.setPageNo(chunk.getPageNo()); mapped.setPageRange(chunk.getPageRange()); mapped.setBboxJson(chunk.getBboxJson());
            mapped.setText(chunk.getText()); mapped.setContentWithWeight(chunk.getContentWithWeight()); mapped.setSourceBlockIds(chunk.getSourceBlockIds());
            mapped.setMetadata(chunk.getMetadata()); return mapped;
        }).toList());
        return result;
    }

    private RaptorBuildResponse fromTransport(RagToolsRaptorBuildResponse source) {
        RaptorBuildResponse result = new RaptorBuildResponse();
        result.setNodes(source.getNodes() == null ? null : source.getNodes().stream().map(item -> {
            RaptorBuildResponse.Node mapped = new RaptorBuildResponse.Node();
            mapped.setId(item.getId()); mapped.setParentId(item.getParentId()); mapped.setLevel(item.getLevel()); mapped.setNodeNo(item.getNodeNo());
            mapped.setTitle(item.getTitle()); mapped.setSummary(item.getSummary()); mapped.setSummaryWithWeight(item.getSummaryWithWeight());
            mapped.setChildNodeIds(item.getChildNodeIds()); mapped.setSourceChunkIds(item.getSourceChunkIds()); mapped.setSourceParentBlockIds(item.getSourceParentBlockIds());
            mapped.setSectionPath(item.getSectionPath()); mapped.setPageRange(item.getPageRange()); mapped.setKeywords(item.getKeywords());
            mapped.setQuestions(item.getQuestions()); mapped.setQualityScore(item.getQualityScore()); mapped.setMetadata(item.getMetadata()); return mapped;
        }).toList());
        return result;
    }
}
