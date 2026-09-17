package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCommunity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEntity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEvidence;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelation;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCommunityMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEntityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEvidenceMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationMapper;
import org.smartledge.ai.manage.service.DocumentVectorGateway;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagTypedChunkService;
import org.smartledge.ai.manage.service.keyword.DocumentKeywordSearchGateway;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagTypedChunkMetadataSupport;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagRelationAuthority;
import org.smartledge.ai.manage.support.MybatisBatchExecutor;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentChunkSourceTypeEnum;
import org.smartledge.enums.DocumentVectorStatusEnum;
import org.smartledge.enums.DocumentVectorStoreTypeEnum;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Service
public class GraphRagTypedChunkServiceImpl implements GraphRagTypedChunkService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };
    private static final int MAX_EVIDENCE_QUOTES = 4;
    private static final int MAX_COMMUNITY_ENTITY_NAMES = 16;
    private static final int MAX_COMMUNITY_RELATION_PATHS = 12;

    private final SuperAgentKgEntityMapper entityMapper;
    private final SuperAgentKgRelationMapper relationMapper;
    private final SuperAgentKgEvidenceMapper evidenceMapper;
    private final SuperAgentKgCommunityMapper communityMapper;
    private final SuperAgentDocumentChunkMapper chunkMapper;
    private final DocumentVectorGateway vectorGateway;
    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;
    private final ObjectMapper objectMapper;
    private final UidGenerator uidGenerator;
    private final GraphRagTypedChunkMetadataSupport metadataSupport;

    @Override
    public List<SuperAgentDocumentChunk> replaceTypedIndex(Long documentId,
                                                           Long taskId,
                                                           Long planId,
                                                           List<SuperAgentDocumentChunk> sourceChunks,
                                                           int startChunkNo) {
        List<SuperAgentDocumentChunk> desiredChunks = buildTypedChunks(
            documentId,
            taskId,
            planId,
            sourceChunks,
            startChunkNo
        );
        deleteExistingTypedIndex(documentId, taskId);
        if (desiredChunks.isEmpty()) {
            return List.of();
        }
        MybatisBatchExecutor.insertBatch(SuperAgentDocumentChunk.class, desiredChunks);
        vectorGateway.vectorize(desiredChunks);
        DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
        if (keywordSearchGateway != null) {
            keywordSearchGateway.indexChunks(desiredChunks);
        }
        MybatisBatchExecutor.updateBatchById(SuperAgentDocumentChunk.class, desiredChunks);
        log.info("GraphRAG typed projection replaced: documentId={}, taskId={}, chunkCount={}",
            documentId, taskId, desiredChunks.size());
        return desiredChunks;
    }

    List<SuperAgentDocumentChunk> buildTypedChunks(Long documentId,
                                                   Long taskId,
                                                   Long planId,
                                                   List<SuperAgentDocumentChunk> sourceChunks,
                                                   int startChunkNo) {
        if (documentId == null || taskId == null) {
            return List.of();
        }

        List<SuperAgentKgEntity> entities = listEntities(documentId, taskId);
        List<SuperAgentKgRelation> relations = listRelations(documentId, taskId);
        List<SuperAgentKgEvidence> evidences = listEvidences(documentId, taskId);
        List<SuperAgentKgCommunity> communities = listCommunities(documentId, taskId);
        if (entities.isEmpty() && relations.isEmpty() && communities.isEmpty()) {
            return List.of();
        }

        Map<Long, SuperAgentDocumentChunk> chunkMap = indexChunks(sourceChunks);
        Map<Long, SuperAgentKgEntity> entityMap = indexById(entities);
        Map<Long, SuperAgentKgRelation> relationMap = indexById(relations);
        Map<Long, List<SuperAgentKgEvidence>> evidenceByEntity = evidences.stream()
            .filter(evidence -> evidence.getEntityId() != null)
            .collect(Collectors.groupingBy(
                SuperAgentKgEvidence::getEntityId,
                LinkedHashMap::new,
                Collectors.toList()
            ));
        Map<Long, List<SuperAgentKgEvidence>> evidenceByRelation = evidences.stream()
            .filter(evidence -> evidence.getRelationId() != null)
            .collect(Collectors.groupingBy(
                SuperAgentKgEvidence::getRelationId,
                LinkedHashMap::new,
                Collectors.toList()
            ));
        Map<Long, SuperAgentKgEvidence> evidenceMap = indexById(evidences);

        List<SuperAgentDocumentChunk> chunks = new ArrayList<>();
        int chunkNo = Math.max(1, startChunkNo);
        for (SuperAgentKgEntity entity : entities) {
            SuperAgentDocumentChunk chunk = buildEntityChunk(documentId, taskId, planId, chunkNo, entity,
                evidenceByEntity.getOrDefault(entity.getId(), List.of()), chunkMap);
            if (chunk != null) {
                chunks.add(chunk);
                chunkNo++;
            }
        }
        for (SuperAgentKgRelation relation : relations) {
            SuperAgentDocumentChunk chunk = buildRelationChunk(documentId, taskId, planId, chunkNo, relation,
                entityMap, evidenceByRelation.getOrDefault(relation.getId(), List.of()), chunkMap);
            if (chunk != null) {
                chunks.add(chunk);
                chunkNo++;
            }
        }
        for (SuperAgentKgCommunity community : communities) {
            SuperAgentDocumentChunk chunk = buildCommunityChunk(documentId, taskId, planId, chunkNo, community,
                entityMap, relationMap, evidenceMap, chunkMap);
            if (chunk != null) {
                chunks.add(chunk);
                chunkNo++;
            }
        }

        log.info("GraphRAG typed chunk 构造完成: documentId={}, taskId={}, chunkCount={}",
            documentId, taskId, chunks.size());
        return chunks;
    }

    private void deleteExistingTypedIndex(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        List<SuperAgentDocumentChunk> existingChunks = chunkMapper.selectList(
            new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getDocumentId, documentId)
                .eq(SuperAgentDocumentChunk::getTaskId, taskId)
                .eq(SuperAgentDocumentChunk::getSourceType, DocumentChunkSourceTypeEnum.GRAPH_RAG.getCode())
        );
        List<Long> chunkIds = existingChunks == null ? List.of() : existingChunks.stream()
            .map(SuperAgentDocumentChunk::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (!chunkIds.isEmpty()) {
            vectorGateway.deleteByChunkIds(documentId, taskId, chunkIds);
            DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
            if (keywordSearchGateway != null) {
                keywordSearchGateway.deleteByChunkIds(documentId, taskId, chunkIds);
            }
        }
        chunkMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getDocumentId, documentId)
            .eq(SuperAgentDocumentChunk::getTaskId, taskId)
            .eq(SuperAgentDocumentChunk::getSourceType, DocumentChunkSourceTypeEnum.GRAPH_RAG.getCode()));
    }

    private SuperAgentDocumentChunk buildEntityChunk(Long documentId,
                                                     Long taskId,
                                                     Long planId,
                                                     int chunkNo,
                                                     SuperAgentKgEntity entity,
                                                     List<SuperAgentKgEvidence> evidences,
                                                     Map<Long, SuperAgentDocumentChunk> chunkMap) {
        if (entity == null || entity.getId() == null || StrUtil.isBlank(entity.getName())) {
            return null;
        }
        Map<String, Object> metadata = readMap(entity.getMetadataJson());
        List<String> aliases = stringList(metadata.get("aliases"));
        List<Long> sourceChunkIds = mergeLongs(longList(metadata.get("sourceChunkIds")), evidenceChunkIds(evidences));
        EvidenceAnchor anchor = resolveAnchor(evidences, sourceChunkIds, chunkMap);
        if (anchor == null || anchor.parentBlockId() == null) {
            return null;
        }

        String confidence = numberText(metadata.get("confidence"));
        String rankBoost = numberText(metadata.get("rankBoost"));
        String rankPosition = numberText(metadata.get("rankPosition"));
        String degree = numberText(metadata.get("degree"));
        List<String> quotes = evidenceQuotes(evidences);
        String text = joinSections(
            "[GraphRAG 实体]",
            "实体：" + entity.getName(),
            "类型：" + StrUtil.blankToDefault(entity.getEntityType(), "CONCEPT"),
            aliases.isEmpty() ? "" : "别名：" + String.join("、", aliases),
            StrUtil.isBlank(confidence) ? "" : "置信度：" + confidence,
            StrUtil.isBlank(rankBoost) ? "" : "图谱Rank：" + rankBoost,
            StrUtil.isBlank(rankPosition) ? "" : "Rank位置：" + rankPosition,
            StrUtil.isBlank(entity.getDescription()) ? "" : "描述：" + entity.getDescription(),
            quotes.isEmpty() ? "" : "证据：\n" + bulletLines(quotes)
        );
        String contentWithWeight = joinSections(
            "GraphRAG entity typed chunk",
            "实体 " + entity.getName(),
            "实体类型 " + StrUtil.blankToDefault(entity.getEntityType(), "CONCEPT"),
            aliases.isEmpty() ? "" : "别名 " + String.join(" ", aliases),
            StrUtil.isBlank(rankBoost) ? "" : "图谱Rank " + rankBoost,
            StrUtil.isBlank(rankPosition) ? "" : "Rank位置 " + rankPosition,
            StrUtil.isBlank(degree) ? "" : "图谱度数 " + degree,
            "实体描述 " + StrUtil.blankToDefault(entity.getDescription(), ""),
            "证据 " + String.join(" ", quotes)
        );

        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_TYPE, "entity");
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, entity.getId());
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, entity.getName());
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_ENTITY_TYPE, entity.getEntityType());
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, anchor.evidenceId());
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_EVIDENCE_IDS, evidenceIds(evidences));
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_SOURCE_CHUNK_IDS, sourceChunkIds);
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_SOURCE_PARENT_BLOCK_IDS, evidenceParentBlockIds(evidences, chunkMap));
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_RANK_BOOST, metadata.get("rankBoost"));
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_PAGERANK, metadata.get("pagerank"));
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_RANK_POSITION, metadata.get("rankPosition"));
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_DEGREE, metadata.get("degree"));

        return baseChunk(documentId, taskId, planId, chunkNo, anchor,
            GraphRagTypedChunkMetadataSupport.CHUNK_TYPE_ENTITY,
            entity.getName(),
            text,
            contentWithWeight,
            jsonArray(keywordValues("GraphRAG", "entity", "实体", entity.getName(), entity.getEntityType(), aliases)),
            jsonArray(questionValues(entity.getName() + " 是什么？", entity.getName() + " 在文档中有哪些证据？")),
            metadataSupport.writeSourceMetadata(sourceMetadata));
    }

    private SuperAgentDocumentChunk buildRelationChunk(Long documentId,
                                                       Long taskId,
                                                       Long planId,
                                                       int chunkNo,
                                                       SuperAgentKgRelation relation,
                                                       Map<Long, SuperAgentKgEntity> entityMap,
                                                       List<SuperAgentKgEvidence> evidences,
                                                       Map<Long, SuperAgentDocumentChunk> chunkMap) {
        if (relation == null || relation.getId() == null) {
            return null;
        }
        SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
        SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
        if (source == null || target == null) {
            return null;
        }
        EvidenceAnchor anchor = resolveAnchor(evidences, evidenceChunkIds(evidences), chunkMap);
        if (anchor == null || anchor.parentBlockId() == null) {
            return null;
        }

        Map<String, Object> metadata = readMap(relation.getMetadataJson());
        String confidence = numberText(metadata.get("confidence"));
        String rankBoost = numberText(metadata.get("rankBoost"));
        String relationLabel = GraphRagRelationAuthority.presentationLabel(metadata);
        String graphPath = source.getName() + " -[" + relationLabel
            + "]-> " + target.getName();
        List<String> quotes = evidenceQuotes(evidences);
        String text = joinSections(
            "[GraphRAG 关系]",
            "路径：" + graphPath,
            StrUtil.isBlank(confidence) ? "" : "置信度：" + confidence,
            StrUtil.isBlank(rankBoost) ? "" : "图谱Rank：" + rankBoost,
            relation.getWeight() == null ? "" : "权重：" + relation.getWeight().toPlainString(),
            StrUtil.isBlank(relation.getDescription()) ? "" : "关系说明：" + relation.getDescription(),
            quotes.isEmpty() ? "" : "证据：\n" + bulletLines(quotes)
        );
        String contentWithWeight = joinSections(
            "GraphRAG relation typed chunk",
            "关系路径 " + graphPath,
            "原文关系谓词 " + relationLabel,
            "起点实体 " + source.getName(),
            "终点实体 " + target.getName(),
            StrUtil.isBlank(rankBoost) ? "" : "图谱Rank " + rankBoost,
            "关系说明 " + StrUtil.blankToDefault(relation.getDescription(), ""),
            "证据 " + String.join(" ", quotes)
        );

        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_TYPE, "relation");
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, source.getId());
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, source.getName());
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_ID, target.getId());
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME, target.getName());
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_ID, relation.getId());
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, relation.getRelationType());
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, graphPath);
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, anchor.evidenceId());
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_EVIDENCE_IDS, evidenceIds(evidences));
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_SOURCE_CHUNK_IDS, evidenceChunkIds(evidences));
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_SOURCE_PARENT_BLOCK_IDS, evidenceParentBlockIds(evidences, chunkMap));
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_RANK_BOOST, metadata.get("rankBoost"));

        return baseChunk(documentId, taskId, planId, chunkNo, anchor,
            GraphRagTypedChunkMetadataSupport.CHUNK_TYPE_RELATION,
            graphPath,
            text,
            contentWithWeight,
            jsonArray(keywordValues("GraphRAG", "relation", "关系", relationLabel, source.getName(), target.getName())),
            jsonArray(questionValues(source.getName() + " 和 " + target.getName() + " 是什么关系？", graphPath + " 有哪些证据？")),
            metadataSupport.writeSourceMetadata(sourceMetadata));
    }

    private SuperAgentDocumentChunk buildCommunityChunk(Long documentId,
                                                        Long taskId,
                                                        Long planId,
                                                        int chunkNo,
                                                        SuperAgentKgCommunity community,
                                                        Map<Long, SuperAgentKgEntity> entityMap,
                                                        Map<Long, SuperAgentKgRelation> relationMap,
                                                        Map<Long, SuperAgentKgEvidence> evidenceMap,
                                                        Map<Long, SuperAgentDocumentChunk> chunkMap) {
        if (community == null || community.getId() == null) {
            return null;
        }
        List<Long> entityIds = readLongList(community.getEntityIdsJson());
        List<Long> relationIds = readLongList(community.getRelationIdsJson());
        List<Long> evidenceIds = readLongList(community.getEvidenceIdsJson());
        List<SuperAgentKgEvidence> evidences = evidenceIds.stream()
            .map(evidenceMap::get)
            .filter(Objects::nonNull)
            .toList();
        EvidenceAnchor anchor = resolveAnchor(evidences, evidenceChunkIds(evidences), chunkMap);
        if (anchor == null || anchor.parentBlockId() == null) {
            return null;
        }

        List<String> entityNames = entityIds.stream()
            .map(entityMap::get)
            .filter(Objects::nonNull)
            .map(SuperAgentKgEntity::getName)
            .filter(StrUtil::isNotBlank)
            .limit(MAX_COMMUNITY_ENTITY_NAMES)
            .toList();
        List<String> relationPaths = relationIds.stream()
            .map(relationMap::get)
            .filter(Objects::nonNull)
            .map(relation -> relationPath(relation, entityMap))
            .filter(StrUtil::isNotBlank)
            .limit(MAX_COMMUNITY_RELATION_PATHS)
            .toList();
        List<String> quotes = evidenceQuotes(evidences);
        Map<String, Object> metadata = readMap(community.getMetadataJson());
        String rankBoost = numberText(metadata.get("rankBoost"));
        String title = StrUtil.blankToDefault(community.getTitle(), "GraphRAG 社区 " + community.getCommunityNo());
        String text = joinSections(
            "[GraphRAG 社区]",
            "社区：" + title,
            "摘要：" + StrUtil.blankToDefault(community.getSummary(), ""),
            StrUtil.isBlank(rankBoost) ? "" : "图谱Rank：" + rankBoost,
            entityNames.isEmpty() ? "" : "核心实体：" + String.join("、", entityNames),
            relationPaths.isEmpty() ? "" : "核心关系：\n" + bulletLines(relationPaths),
            quotes.isEmpty() ? "" : "证据：\n" + bulletLines(quotes)
        );
        String contentWithWeight = joinSections(
            "GraphRAG community typed chunk",
            "社区 " + title,
            "社区摘要 " + StrUtil.blankToDefault(community.getSummary(), ""),
            StrUtil.isBlank(rankBoost) ? "" : "图谱Rank " + rankBoost,
            "核心实体 " + String.join(" ", entityNames),
            "核心关系 " + String.join(" ", relationPaths),
            "证据 " + String.join(" ", quotes)
        );

        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_TYPE, "community");
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_ID, community.getId());
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE, title);
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY, community.getSummary());
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, anchor.evidenceId());
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_ENTITY_IDS, entityIds);
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_RELATION_IDS, relationIds);
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_EVIDENCE_IDS, evidenceIds);
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_SOURCE_CHUNK_IDS, evidenceChunkIds(evidences));
        sourceMetadata.put(GraphRagTypedChunkMetadataSupport.KG_SOURCE_PARENT_BLOCK_IDS, evidenceParentBlockIds(evidences, chunkMap));
        sourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_RANK_BOOST, metadata.get("rankBoost"));

        return baseChunk(documentId, taskId, planId, chunkNo, anchor,
            GraphRagTypedChunkMetadataSupport.CHUNK_TYPE_COMMUNITY,
            title,
            text,
            contentWithWeight,
            jsonArray(keywordValues("GraphRAG", "community", "社区", title, entityNames)),
            jsonArray(questionValues(title + " 概括了哪些内容？", title + " 包含哪些实体和关系？")),
            metadataSupport.writeSourceMetadata(sourceMetadata));
    }

    private SuperAgentDocumentChunk baseChunk(Long documentId,
                                              Long taskId,
                                              Long planId,
                                              int chunkNo,
                                              EvidenceAnchor anchor,
                                              String chunkType,
                                              String title,
                                              String chunkText,
                                              String contentWithWeight,
                                              String keywords,
                                              String questions,
                                              String sourceBlockIds) {
        SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
        chunk.setId(uidGenerator.getUid());
        chunk.setDocumentId(documentId);
        chunk.setTaskId(taskId);
        chunk.setPlanId(planId);
        chunk.setParentBlockId(anchor.parentBlockId());
        chunk.setChunkNo(chunkNo);
        chunk.setSourceType(DocumentChunkSourceTypeEnum.GRAPH_RAG.getCode());
        chunk.setSectionPath(anchor.sectionPath());
        chunk.setStructureNodeId(anchor.structureNodeId());
        chunk.setStructureNodeType(anchor.structureNodeType());
        chunk.setCanonicalPath(anchor.canonicalPath());
        chunk.setItemIndex(anchor.itemIndex());
        chunk.setChunkText(chunkText);
        chunk.setContentWithWeight(contentWithWeight);
        chunk.setChunkType(chunkType);
        chunk.setTitle(limit(title, 1000));
        chunk.setKeywords(keywords);
        chunk.setQuestions(questions);
        chunk.setCharCount(chunkText == null ? 0 : chunkText.length());
        chunk.setTokenCount(estimateTokenCount(chunkText));
        chunk.setVectorStatus(DocumentVectorStatusEnum.WAIT_VECTOR.getCode());
        chunk.setVectorStoreType(DocumentVectorStoreTypeEnum.PG_VECTOR.getCode());
        chunk.setPageNo(anchor.pageNo());
        chunk.setPageRange(anchor.pageRange());
        chunk.setBboxJson(anchor.bboxJson());
        chunk.setSourceBlockIds(sourceBlockIds);
        chunk.setStatus(BusinessStatus.YES.getCode());
        return chunk;
    }

    private List<SuperAgentKgEntity> listEntities(Long documentId, Long taskId) {
        return entityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getDocumentId, documentId)
            .eq(SuperAgentKgEntity::getTaskId, taskId)
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKgEntity::getId));
    }

    private List<SuperAgentKgRelation> listRelations(Long documentId, Long taskId) {
        return relationMapper.selectList(new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, documentId)
            .eq(SuperAgentKgRelation::getTaskId, taskId)
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKgRelation::getId));
    }

    private List<SuperAgentKgEvidence> listEvidences(Long documentId, Long taskId) {
        return evidenceMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getDocumentId, documentId)
            .eq(SuperAgentKgEvidence::getTaskId, taskId)
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKgEvidence::getId));
    }

    private List<SuperAgentKgCommunity> listCommunities(Long documentId, Long taskId) {
        return communityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .eq(SuperAgentKgCommunity::getDocumentId, documentId)
            .eq(SuperAgentKgCommunity::getTaskId, taskId)
            .eq(SuperAgentKgCommunity::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKgCommunity::getCommunityNo)
            .orderByAsc(SuperAgentKgCommunity::getId));
    }

    private EvidenceAnchor resolveAnchor(List<SuperAgentKgEvidence> evidences,
                                         Collection<Long> fallbackChunkIds,
                                         Map<Long, SuperAgentDocumentChunk> chunkMap) {
        if (CollUtil.isNotEmpty(evidences)) {
            for (SuperAgentKgEvidence evidence : evidences) {
                EvidenceAnchor anchor = anchorFromEvidence(evidence, chunkMap);
                if (anchor != null && anchor.parentBlockId() != null) {
                    return anchor;
                }
            }
        }
        if (CollUtil.isNotEmpty(fallbackChunkIds)) {
            for (Long chunkId : fallbackChunkIds) {
                SuperAgentDocumentChunk chunk = chunkMap.get(chunkId);
                if (chunk != null && chunk.getParentBlockId() != null) {
                    return new EvidenceAnchor(
                        chunk.getParentBlockId(),
                        null,
                        chunk.getSectionPath(),
                        chunk.getStructureNodeId(),
                        chunk.getStructureNodeType(),
                        chunk.getCanonicalPath(),
                        chunk.getItemIndex(),
                        chunk.getPageNo(),
                        chunk.getPageRange(),
                        chunk.getBboxJson()
                    );
                }
            }
        }
        return null;
    }

    private EvidenceAnchor anchorFromEvidence(SuperAgentKgEvidence evidence,
                                              Map<Long, SuperAgentDocumentChunk> chunkMap) {
        if (evidence == null) {
            return null;
        }
        SuperAgentDocumentChunk sourceChunk = evidence.getChunkId() == null ? null : chunkMap.get(evidence.getChunkId());
        Long parentBlockId = evidence.getParentBlockId();
        if (parentBlockId == null && sourceChunk != null) {
            parentBlockId = sourceChunk.getParentBlockId();
        }
        return new EvidenceAnchor(
            parentBlockId,
            evidence.getId(),
            firstNonBlank(evidence.getSectionPath(), sourceChunk == null ? null : sourceChunk.getSectionPath()),
            sourceChunk == null ? null : sourceChunk.getStructureNodeId(),
            sourceChunk == null ? null : sourceChunk.getStructureNodeType(),
            sourceChunk == null ? null : sourceChunk.getCanonicalPath(),
            sourceChunk == null ? null : sourceChunk.getItemIndex(),
            evidence.getPageNo() == null && sourceChunk != null ? sourceChunk.getPageNo() : evidence.getPageNo(),
            firstNonBlank(evidence.getPageRange(), sourceChunk == null ? null : sourceChunk.getPageRange()),
            firstNonBlank(evidence.getBboxJson(), sourceChunk == null ? null : sourceChunk.getBboxJson())
        );
    }

    private String relationPath(SuperAgentKgRelation relation, Map<Long, SuperAgentKgEntity> entityMap) {
        SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
        SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
        if (source == null || target == null) {
            return "";
        }
        return source.getName() + " -[" + GraphRagRelationAuthority.presentationLabel(readMap(relation.getMetadataJson()))
            + "]-> " + target.getName();
    }

    private Map<Long, SuperAgentDocumentChunk> indexChunks(List<SuperAgentDocumentChunk> sourceChunks) {
        if (CollUtil.isEmpty(sourceChunks)) {
            return Map.of();
        }
        return sourceChunks.stream()
            .filter(chunk -> chunk != null && chunk.getId() != null)
            .collect(Collectors.toMap(
                SuperAgentDocumentChunk::getId,
                chunk -> chunk,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private <T> Map<Long, T> indexById(Collection<T> values) {
        if (CollUtil.isEmpty(values)) {
            return Map.of();
        }
        Map<Long, T> result = new LinkedHashMap<>();
        for (T value : values) {
            Long id = resolveId(value);
            if (id != null) {
                result.put(id, value);
            }
        }
        return result;
    }

    private Long resolveId(Object value) {
        if (value instanceof SuperAgentKgEntity entity) {
            return entity.getId();
        }
        if (value instanceof SuperAgentKgRelation relation) {
            return relation.getId();
        }
        if (value instanceof SuperAgentKgEvidence evidence) {
            return evidence.getId();
        }
        return null;
    }

    private Map<String, Object> readMap(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        }
        catch (Exception exception) {
            return Map.of();
        }
    }

    private List<Long> readLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LONG_LIST_TYPE);
        }
        catch (Exception exception) {
            return List.of();
        }
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            return List.of(text);
        }
        return List.of();
    }

    private List<Long> longList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .map(this::toLong)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        }
        Long single = toLong(value);
        return single == null ? List.of() : List.of(single);
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return Long.parseLong(text.trim());
            }
            catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private String numberText(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            return String.valueOf(number.doubleValue());
        }
        return value == null ? "" : String.valueOf(value);
    }

    private List<Long> evidenceIds(List<SuperAgentKgEvidence> evidences) {
        return evidences.stream()
            .map(SuperAgentKgEvidence::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private List<Long> evidenceChunkIds(List<SuperAgentKgEvidence> evidences) {
        return evidences.stream()
            .map(SuperAgentKgEvidence::getChunkId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private List<Long> evidenceParentBlockIds(List<SuperAgentKgEvidence> evidences,
                                              Map<Long, SuperAgentDocumentChunk> chunkMap) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (SuperAgentKgEvidence evidence : evidences) {
            if (evidence.getParentBlockId() != null) {
                result.add(evidence.getParentBlockId());
                continue;
            }
            SuperAgentDocumentChunk chunk = evidence.getChunkId() == null ? null : chunkMap.get(evidence.getChunkId());
            if (chunk != null && chunk.getParentBlockId() != null) {
                result.add(chunk.getParentBlockId());
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> evidenceQuotes(List<SuperAgentKgEvidence> evidences) {
        return evidences.stream()
            .sorted(Comparator.comparing(SuperAgentKgEvidence::getId, Comparator.nullsLast(Long::compareTo)))
            .map(SuperAgentKgEvidence::getQuoteText)
            .filter(StrUtil::isNotBlank)
            .map(quote -> limit(quote.trim(), 260))
            .distinct()
            .limit(MAX_EVIDENCE_QUOTES)
            .toList();
    }

    private List<Long> mergeLongs(Collection<Long> left, Collection<Long> right) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (left != null) {
            result.addAll(left);
        }
        if (right != null) {
            result.addAll(right);
        }
        return new ArrayList<>(result);
    }

    private List<String> keywordValues(Object... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof Collection<?> collection) {
                for (Object item : collection) {
                    addKeyword(result, item);
                }
            }
            else {
                addKeyword(result, value);
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> questionValues(String... questions) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String question : questions) {
            if (StrUtil.isNotBlank(question)) {
                result.add(question);
            }
        }
        return new ArrayList<>(result);
    }

    private void addKeyword(Set<String> values, Object value) {
        if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
            values.add(String.valueOf(value));
        }
    }

    private String jsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("GraphRAG typed chunk 数组元数据序列化失败", exception);
        }
    }

    private String joinSections(String... sections) {
        List<String> result = new ArrayList<>();
        for (String section : sections) {
            if (StrUtil.isNotBlank(section)) {
                result.add(section.trim());
            }
        }
        return String.join("\n", result);
    }

    private String bulletLines(List<String> values) {
        return values.stream()
            .filter(StrUtil::isNotBlank)
            .map(value -> "- " + value)
            .collect(Collectors.joining("\n"));
    }

    private String firstNonBlank(String first, String second) {
        return StrUtil.isNotBlank(first) ? first : second;
    }

    private int estimateTokenCount(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        return Math.max(1, text.length() / 2);
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private record EvidenceAnchor(Long parentBlockId,
                                  Long evidenceId,
                                  String sectionPath,
                                  Long structureNodeId,
                                  Integer structureNodeType,
                                  String canonicalPath,
                                  Integer itemIndex,
                                  Integer pageNo,
                                  String pageRange,
                                  String bboxJson) {
    }
}
