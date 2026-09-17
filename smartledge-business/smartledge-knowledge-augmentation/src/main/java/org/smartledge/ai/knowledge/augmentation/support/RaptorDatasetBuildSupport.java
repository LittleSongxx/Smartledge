package org.smartledge.ai.knowledge.augmentation.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentRaptorNode;
import org.smartledge.ai.knowledge.augmentation.model.RaptorBuildRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class RaptorDatasetBuildSupport {

    public static final String INPUT_MODE_DOCUMENT_RAPTOR_SUMMARY = "DOCUMENT_RAPTOR_SUMMARY";

    public static final String INPUT_MODE_ORIGINAL_CHUNK = "ORIGINAL_CHUNK";

    private static final String DATASET_INPUT_TYPE_SUMMARY = "DOCUMENT_RAPTOR_SUMMARY";

    private static final String DATASET_INPUT_TYPE_CHUNK = "ORIGINAL_CHUNK";

    private final ObjectMapper objectMapper;

    public RaptorDatasetBuildSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DatasetInputs buildInputs(List<SuperAgentRaptorNode> reusableSummaryNodes,
                                     List<SuperAgentDocumentChunk> originalChunks) {
        List<DatasetInput> summaryInputs = CollUtil.emptyIfNull(reusableSummaryNodes).stream()
            .filter(Objects::nonNull)
            .filter(node -> node.getId() != null && StrUtil.isNotBlank(node.getSummary()))
            .map(node -> DatasetInput.fromSummaryNode(
                node,
                readLongList(node.getSourceChunkIdsJson()),
                readLongList(node.getSourceParentBlockIdsJson())
            ))
            .filter(input -> CollUtil.isNotEmpty(input.originalChunkIds()))
            .toList();
        if (CollUtil.isNotEmpty(summaryInputs)) {
            return new DatasetInputs(INPUT_MODE_DOCUMENT_RAPTOR_SUMMARY, summaryInputs);
        }
        List<DatasetInput> chunkInputs = CollUtil.emptyIfNull(originalChunks).stream()
            .filter(Objects::nonNull)
            .filter(chunk -> chunk.getId() != null && StrUtil.isNotBlank(chunk.getChunkText()))
            .map(DatasetInput::fromOriginalChunk)
            .toList();
        return new DatasetInputs(INPUT_MODE_ORIGINAL_CHUNK, chunkInputs);
    }

    public RaptorBuildRequest.Chunk toRequestChunk(DatasetInput input, int chunkNo) {
        RaptorBuildRequest.Chunk requestChunk = new RaptorBuildRequest.Chunk();
        requestChunk.setChunkId(input.chunkId());
        requestChunk.setParentBlockId(input.parentBlockId());
        requestChunk.setChunkNo(chunkNo);
        requestChunk.setChunkType(blankToEmpty(input.chunkType()));
        requestChunk.setTitle(blankToEmpty(input.title()));
        requestChunk.setSectionPath(blankToEmpty(input.sectionPath()));
        requestChunk.setPageNo(input.pageNo());
        requestChunk.setPageRange(blankToEmpty(input.pageRange()));
        requestChunk.setBboxJson(blankToEmpty(input.bboxJson()));
        requestChunk.setText(blankToEmpty(input.text()));
        requestChunk.setContentWithWeight(blankToEmpty(StrUtil.blankToDefault(input.contentWithWeight(), input.text())));
        requestChunk.setSourceBlockIds(blankToEmpty(input.sourceBlockIds()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("datasetInputType", input.inputType());
        metadata.put("documentId", input.documentId());
        metadata.put("taskId", input.taskId());
        if (input.sourceRaptorNodeId() != null) {
            metadata.put("sourceRaptorNodeId", input.sourceRaptorNodeId());
            metadata.put("sourceRaptorNodeLevel", input.sourceRaptorNodeLevel());
        }
        metadata.put("sourceOriginalChunkIds", input.originalChunkIds());
        metadata.put("sourceOriginalParentBlockIds", input.originalParentBlockIds());
        requestChunk.setMetadata(metadata);
        return requestChunk;
    }

    private String blankToEmpty(String value) {
        return StrUtil.blankToDefault(value, "");
    }

    private List<Long> readLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            List<Long> values = objectMapper.readValue(json, new TypeReference<>() {
            });
            return values == null ? List.of() : values.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAPTOR dataset source id JSON 解析失败", exception);
        }
    }

    public record DatasetInputs(String inputMode, List<DatasetInput> inputs) {

        public int reusableSummaryInputCount() {
            return countByInputType(DATASET_INPUT_TYPE_SUMMARY);
        }

        public int originalChunkInputCount() {
            return countByInputType(DATASET_INPUT_TYPE_CHUNK);
        }

        public List<Long> expandSourceChunkIds(List<Long> inputIds) {
            return expand(inputIds, true);
        }

        public List<Long> expandSourceParentBlockIds(List<Long> inputIds) {
            return expand(inputIds, false);
        }

        private List<Long> expand(List<Long> inputIds, boolean chunkIds) {
            if (CollUtil.isEmpty(inputIds) || CollUtil.isEmpty(inputs)) {
                return List.of();
            }
            Map<Long, DatasetInput> inputById = new LinkedHashMap<>();
            for (DatasetInput input : inputs) {
                inputById.put(input.chunkId(), input);
            }
            LinkedHashSet<Long> result = new LinkedHashSet<>();
            for (Long inputId : inputIds) {
                DatasetInput input = inputById.get(inputId);
                if (input == null) {
                    result.add(inputId);
                    continue;
                }
                List<Long> expanded = chunkIds ? input.originalChunkIds() : input.originalParentBlockIds();
                if (CollUtil.isEmpty(expanded)) {
                    if (chunkIds) {
                        result.add(inputId);
                    }
                }
                else {
                    result.addAll(expanded);
                }
            }
            return new ArrayList<>(result);
        }

        private int countByInputType(String inputType) {
            if (CollUtil.isEmpty(inputs)) {
                return 0;
            }
            return (int) inputs.stream()
                .filter(input -> Objects.equals(inputType, input.inputType()))
                .count();
        }
    }

    public record DatasetInput(Long chunkId,
                               Long parentBlockId,
                               Integer chunkNo,
                               String chunkType,
                               String title,
                               String sectionPath,
                               Integer pageNo,
                               String pageRange,
                               String bboxJson,
                               String text,
                               String contentWithWeight,
                               String sourceBlockIds,
                               String inputType,
                               Long documentId,
                               Long taskId,
                               Long sourceRaptorNodeId,
                               Integer sourceRaptorNodeLevel,
                               List<Long> originalChunkIds,
                               List<Long> originalParentBlockIds) {

        public static DatasetInput fromSummaryNode(SuperAgentRaptorNode node,
                                                   List<Long> originalChunkIds,
                                                   List<Long> originalParentBlockIds) {
            return new DatasetInput(
                node.getId(),
                first(originalParentBlockIds),
                node.getNodeNo(),
                "RAPTOR_SUMMARY",
                node.getTitle(),
                node.getSectionPath(),
                null,
                node.getPageRange(),
                null,
                node.getSummary(),
                StrUtil.blankToDefault(node.getSummaryWithWeight(), node.getSummary()),
                null,
                DATASET_INPUT_TYPE_SUMMARY,
                node.getDocumentId(),
                node.getTaskId(),
                node.getId(),
                node.getNodeLevel(),
                List.copyOf(CollUtil.emptyIfNull(originalChunkIds)),
                List.copyOf(CollUtil.emptyIfNull(originalParentBlockIds))
            );
        }

        public static DatasetInput fromOriginalChunk(SuperAgentDocumentChunk chunk) {
            return new DatasetInput(
                chunk.getId(),
                chunk.getParentBlockId(),
                chunk.getChunkNo(),
                chunk.getChunkType(),
                chunk.getTitle(),
                chunk.getSectionPath(),
                chunk.getPageNo(),
                chunk.getPageRange(),
                chunk.getBboxJson(),
                chunk.getChunkText(),
                StrUtil.blankToDefault(chunk.getContentWithWeight(), chunk.getChunkText()),
                chunk.getSourceBlockIds(),
                DATASET_INPUT_TYPE_CHUNK,
                chunk.getDocumentId(),
                chunk.getTaskId(),
                null,
                null,
                List.of(chunk.getId()),
                chunk.getParentBlockId() == null ? List.of() : List.of(chunk.getParentBlockId())
            );
        }

        private static Long first(List<Long> values) {
            return CollUtil.isEmpty(values) ? null : values.get(0);
        }
    }
}
