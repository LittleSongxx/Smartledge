package org.smartledge.ai.knowledge.augmentation.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@AllArgsConstructor
@Component
public class GraphRagTypedChunkMetadataSupport {

    public static final String CHUNK_TYPE_ENTITY = "GRAPH_ENTITY";
    public static final String CHUNK_TYPE_RELATION = "GRAPH_RELATION";
    public static final String CHUNK_TYPE_COMMUNITY = "GRAPH_COMMUNITY";
    public static final String SOURCE_TYPE_GRAPH_RAG = "GRAPH_RAG";
    public static final String CONTEXT_ARTIFACT_GRAPH_TYPED_CHUNK = "GRAPH_TYPED_CHUNK";
    public static final String KG_TYPE = "kgType";
    public static final String KG_ENTITY_TYPE = "kgEntityType";
    public static final String KG_EVIDENCE_IDS = "kgEvidenceIds";
    public static final String KG_SOURCE_CHUNK_IDS = "sourceChunkIds";
    public static final String KG_SOURCE_PARENT_BLOCK_IDS = "sourceParentBlockIds";
    public static final String KG_ENTITY_IDS = "kgEntityIds";
    public static final String KG_RELATION_IDS = "kgRelationIds";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public boolean isGraphTypedChunk(String chunkType) {
        return CHUNK_TYPE_ENTITY.equals(chunkType)
            || CHUNK_TYPE_RELATION.equals(chunkType)
            || CHUNK_TYPE_COMMUNITY.equals(chunkType);
    }

    public String writeSourceMetadata(Map<String, Object> sourceMetadata) {
        try {
            return objectMapper.writeValueAsString(sourceMetadata == null ? Map.of() : sourceMetadata);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("GraphRAG typed chunk 元数据序列化失败", exception);
        }
    }

    public void enrichMetadata(Map<String, Object> metadata, String chunkType, String sourceBlockIds) {
        if (metadata == null || !isGraphTypedChunk(chunkType)) {
            return;
        }
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, SOURCE_TYPE_GRAPH_RAG);
        Map<String, Object> sourceMetadata = readSourceMetadata(sourceBlockIds);
        for (Map.Entry<String, Object> entry : sourceMetadata.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (isKnowledgeBaseMetadataKey(entry.getKey())) {
                mergeKnowledgeBaseMetadata(metadata, entry.getKey(), entry.getValue());
                continue;
            }
            metadata.put(entry.getKey(), entry.getValue());
        }
        metadata.remove(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET);
        metadata.put(
            DocumentKnowledgeMetadataKeys.CONTEXT_ARTIFACT,
            CONTEXT_ARTIFACT_GRAPH_TYPED_CHUNK
        );
    }

    private void mergeKnowledgeBaseMetadata(Map<String, Object> metadata, String key, Object sourceValue) {
        if (!isMeaningfulMetadataValue(sourceValue)) {
            return;
        }
        Object existingValue = metadata.get(key);
        if (isMeaningfulMetadataValue(existingValue)) {
            return;
        }
        metadata.put(key, sourceValue);
    }

    private boolean isKnowledgeBaseMetadataKey(String key) {
        return DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID.equals(key)
            || DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME.equals(key);
    }

    private boolean isMeaningfulMetadataValue(Object value) {
        if (value == null) {
            return false;
        }
        return !(value instanceof String text) || !text.isBlank();
    }

    public Map<String, Object> readSourceMetadata(String sourceBlockIds) {
        if (StrUtil.isBlank(sourceBlockIds)) {
            return Map.of();
        }
        String text = sourceBlockIds.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, MAP_TYPE);
        }
        catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }
}
