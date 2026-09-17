package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentChunk;
import org.smartledge.ai.manage.data.SuperAgentDocumentParentBlock;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.smartledge.ai.manage.model.DocumentRetrieveFilters;
import org.smartledge.ai.manage.model.DocumentRetrieveRequest;
import org.smartledge.ai.manage.model.KnowledgeDocumentDescriptor;
import org.smartledge.ai.manage.model.StructureAnchoredEvidenceRequest;
import org.smartledge.ai.manage.service.DocumentKnowledgeService;
import org.smartledge.ai.manage.service.keyword.DocumentKeywordSearchGateway;
import org.smartledge.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.manage.support.DocumentPgVectorConstants;
import org.smartledge.ai.manage.support.PgVectorTenantOperations;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagTypedChunkMetadataSupport;
import org.smartledge.ai.rag.runtime.support.EvidenceMetadataPriority;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentChunkSourceTypeEnum;
import org.smartledge.enums.DocumentIndexStatusEnum;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@Slf4j
@AllArgsConstructor
@Service
public class DocumentKnowledgeServiceImpl implements DocumentKnowledgeService {

    private static final String STRUCTURE_ANCHOR_CHANNEL = "structure-anchor";
    private static final String MATCH_NODE_ID = "NODE_ID";
    private static final String MATCH_CANONICAL_EXACT = "CANONICAL_EXACT";
    private static final String MATCH_CANONICAL_DESCENDANT = "CANONICAL_DESCENDANT";
    private static final String MATCH_TITLE_SAME_SECTION = "TITLE_SAME_SECTION";
    private static final String BODY_RESOLVED_NODE_TEXT = "NODE_TEXT";
    private static final String BODY_RESOLVED_PARENT_TEXT = "PARENT_TEXT";
    private static final String BODY_RESOLVED_CONTINUATION_LIST = "CONTINUATION_LIST";
    private static final String BODY_RESOLVED_DIRECT_CHILD = "DIRECT_CHILD";
    private static final String BODY_RESOLVED_DESCENDANT = "DESCENDANT";
    private static final String BODY_RESOLVED_NODE_TITLE = "NODE_TITLE";
    private static final String BODY_KIND_TEXT_CHUNK = "TEXT_CHUNK";
    private static final String BODY_KIND_LIST_CONTINUATION = "LIST_CONTINUATION";
    private static final String BODY_KIND_CHILD_SECTION = "CHILD_SECTION";
    private static final String BODY_KIND_SOURCE_HEADING = "SOURCE_HEADING";

    /**
     * 向量检索的租户 over-fetch 因子。
     *
     * <p>ANN 索引遍历不参与 RLS 策略过滤：先在全球索引里取邻居，再按策略丢弃。
     * 因此取 topK 行是不够的 —— 被策略丢掉的邻居会让我们少拿结果。这里按倍数多取，
     * 再在应用层截回 topK；同时 WHERE 里带 {@code tenant_id} 前置条件，
     * 使规划器在索引演进为 {@code (tenant_id, embedding)} 复合索引后可以直接限定扫描范围。</p>
     */
    private static final int TENANT_OVER_FETCH_FACTOR = 4;

    private static final String VECTOR_RETRIEVE_SQL_TEMPLATE = """
        SELECT
            id,
            document_id,
            task_id,
            parent_block_id,
            chunk_no,
            section_path,
            structure_node_id,
            structure_node_type,
            canonical_path,
            item_index,
            chunk_text,
            content_with_weight,
            chunk_type,
            title,
            keywords,
            questions,
            page_no,
            page_range,
            bbox_json,
            source_block_ids,
            1 - (embedding <=> CAST(? AS vector)) AS similarity_score
        FROM %s
        WHERE status = 1
          AND (expires_at IS NULL OR expires_at > NOW())
          AND tenant_id = ?
          AND document_id IN (%s)
          AND task_id IN (%s)
        """;

    private final SuperAgentDocumentMapper documentMapper;
    
    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;

    private final SuperAgentDocumentChunkMapper documentChunkMapper;
    
    @Qualifier("documentManagePgVectorJdbcTemplate")
    private final JdbcTemplate pgVectorJdbcTemplate;

    private final PgVectorTenantOperations pgVectorOperations;
    
    private final ObjectProvider<EmbeddingPort> embeddingModelProvider;
    
    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;

    private final GraphRagTypedChunkMetadataSupport graphRagTypedChunkMetadataSupport;
    
    @Override
    public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {

        return toDescriptors(documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .and(wrapper -> wrapper.isNull(SuperAgentDocument::getExpiresAt)
                .or()
                .gt(SuperAgentDocument::getExpiresAt, java.time.LocalDateTime.now()))
            .orderByDesc(SuperAgentDocument::getEditTime)
            .orderByDesc(SuperAgentDocument::getId)));
    }

    @Override
    public List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(Collection<Long> knowledgeBaseIds) {
        List<Long> ids = knowledgeBaseIds == null
            ? List.of()
            : knowledgeBaseIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return toDescriptors(documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .in(SuperAgentDocument::getKnowledgeBaseId, ids)
            .and(wrapper -> wrapper.isNull(SuperAgentDocument::getExpiresAt)
                .or()
                .gt(SuperAgentDocument::getExpiresAt, java.time.LocalDateTime.now()))
            .orderByDesc(SuperAgentDocument::getEditTime)
            .orderByDesc(SuperAgentDocument::getId)));
    }

    private List<KnowledgeDocumentDescriptor> toDescriptors(List<SuperAgentDocument> documents) {
        if (CollUtil.isEmpty(documents)) {
            return List.of();
        }

        return documents.stream()
            .map(document -> new KnowledgeDocumentDescriptor(
                document.getId(),
                document.getDocumentName(),
                document.getLastIndexTaskId(),
                document.getKnowledgeBaseId(),
                document.getKnowledgeBaseName()
            ))
            .toList();
    }

    @Override
    public List<RetrievalDocument> vectorSearch(DocumentRetrieveRequest request) {
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        EmbeddingPort embeddingModel = requireEmbeddingModel();

        String questionVector = toVectorLiteral(embeddingModel.embed(request.getRetrievalQuery().trim()));
        List<Long> documentIds = request.resolvedDocumentIds();
        List<Long> taskIds = request.resolvedTaskIds();

        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = listDescriptorMap(documentIds);

        ResolvedMetadataScope resolvedScope = resolveMetadataScope(request);
        if (resolvedScope.documentIds().isEmpty() || resolvedScope.taskIds().isEmpty()) {
            return List.of();
        }

        Long tenantId = org.smartledge.ai.manage.support.IndexTenantGuard.searchableTenantId();
        if (tenantId == null) {
            return List.of();
        }

        StringBuilder sqlBuilder = new StringBuilder(VECTOR_RETRIEVE_SQL_TEMPLATE.formatted(
            DocumentPgVectorConstants.EMBEDDING_TABLE_NAME,
            buildPlaceholders(resolvedScope.documentIds().size()),
            buildPlaceholders(resolvedScope.taskIds().size())
        ));
        appendSectionFilters(sqlBuilder, resolvedScope.filters());

        sqlBuilder.append("""

            ORDER BY embedding <=> CAST(? AS vector)
            LIMIT ?
            """);

        List<Object> params = new ArrayList<>();

        params.add(questionVector);
        params.add(tenantId);
        params.addAll(resolvedScope.documentIds());
        params.addAll(resolvedScope.taskIds());
        appendSectionFilterParams(params, resolvedScope.filters());
        params.add(questionVector);
        int topK = resolveTopK(request.getTopK());
        params.add(topK * TENANT_OVER_FETCH_FACTOR);

        List<RetrievalDocument> fetched = pgVectorOperations.query(tenantId, sqlBuilder.toString(),
            (resultSet, rowNum) -> {
            long chunkId = resultSet.getLong("id");
            long documentId = resultSet.getLong("document_id");
            double score = resultSet.getDouble("similarity_score");
            KnowledgeDocumentDescriptor descriptor = descriptorMap.get(documentId);
            return buildRetrievedDocument(
                chunkId,
                resultSet.getString("chunk_text"),
                resultSet.getString("content_with_weight"),
                resultSet.getString("chunk_type"),
                resultSet.getString("title"),
                resultSet.getString("keywords"),
                resultSet.getString("questions"),
                resultSet.getLong("task_id"),
                resultSet.getLong("parent_block_id"),
                resultSet.getInt("chunk_no"),
                resultSet.getString("section_path"),
                getNullableLong(resultSet, "structure_node_id"),
                getNullableInteger(resultSet, "structure_node_type"),
                resultSet.getString("canonical_path"),
                getNullableInteger(resultSet, "item_index"),
                getNullableInteger(resultSet, "page_no"),
                resultSet.getString("page_range"),
                resultSet.getString("bbox_json"),
                resultSet.getString("source_block_ids"),
                descriptor,
                "vector",
                score
            );
            }, params.toArray());
        // over-fetch 只影响取数，返回给上层的仍然是 topK：排序语义不变。
        return fetched.size() <= topK ? fetched : List.copyOf(fetched.subList(0, topK));
    }

    @Override
    public List<RetrievalDocument> keywordSearch(DocumentRetrieveRequest request) {
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        ResolvedMetadataScope resolvedScope = resolveMetadataScope(request);
        if (resolvedScope.documentIds().isEmpty() || resolvedScope.taskIds().isEmpty()) {
            return List.of();
        }

        DocumentRetrieveRequest filteredRequest = new DocumentRetrieveRequest(
            request.getQuestion(),
            request.getRetrievalQuery(),
            resolvedScope.documentIds().isEmpty() ? null : resolvedScope.documentIds().get(0),
            resolvedScope.taskIds().isEmpty() ? null : resolvedScope.taskIds().get(0),
            request.getTopK(),
            resolvedScope.filters(),
            request.getQueryContextHints()
        );
        filteredRequest.setDocumentIds(resolvedScope.documentIds());
        filteredRequest.setTaskIds(resolvedScope.taskIds());

        DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
        if (keywordSearchGateway == null) {
            throw new IllegalStateException("当前未找到可用的 Elasticsearch/BM25 关键词检索服务，无法执行关键词检索。");
        }
        return keywordSearchGateway.search(filteredRequest);
    }

    @Override
    public List<RetrievalDocument> elevateToParentBlocks(List<RetrievalDocument> childDocuments, int maxChars) {
        if (CollUtil.isEmpty(childDocuments)) {
            return List.of();
        }

        Map<Long, List<RetrievalDocument>> childGroupsByParent = new LinkedHashMap<>();
        List<RetrievalDocument> directCandidateDocuments = new ArrayList<>();
        for (RetrievalDocument childDocument : childDocuments) {
            if (childDocument == null) {
                continue;
            }
            Long parentBlockId = asLong(childDocument.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
            if (parentBlockId == null) {
                directCandidateDocuments.add(childDocument);
                continue;
            }
            childGroupsByParent.computeIfAbsent(parentBlockId, ignored -> new ArrayList<>()).add(childDocument);
        }

        if (childGroupsByParent.isEmpty()) {
            return directCandidateDocuments;
        }

        List<Long> parentBlockIds = new ArrayList<>(childGroupsByParent.keySet());
        Map<Long, SuperAgentDocumentParentBlock> parentBlockMap = parentBlockMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
                    .in(SuperAgentDocumentParentBlock::getId, parentBlockIds)
                    .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode())
                    .orderByAsc(SuperAgentDocumentParentBlock::getParentNo)
            ).stream()
            .collect(Collectors.toMap(
                SuperAgentDocumentParentBlock::getId,
                parent -> parent,
                (left, right) -> left,
                LinkedHashMap::new
            ));

        List<RetrievalDocument> elevatedDocuments = new ArrayList<>(childGroupsByParent.size() + directCandidateDocuments.size());
        for (Map.Entry<Long, List<RetrievalDocument>> entry : childGroupsByParent.entrySet()) {
            SuperAgentDocumentParentBlock parentBlock = parentBlockMap.get(entry.getKey());
            if (parentBlock == null) {
                elevatedDocuments.addAll(entry.getValue());
                continue;
            }
            elevatedDocuments.add(buildParentEvidenceDocument(parentBlock, entry.getValue(), maxChars));
        }
        elevatedDocuments.addAll(directCandidateDocuments);
        elevatedDocuments.sort(this::compareEvidenceDocument);
        return elevatedDocuments;
    }

    @Override
    public List<RetrievalDocument> expandStructureAnchoredEvidence(StructureAnchoredEvidenceRequest request) {
        if (request == null) {
            return List.of();
        }
        int maxTotal = request.getMaxTotal() <= 0 ? 4 : request.getMaxTotal();
        int maxPerAnchor = request.getMaxPerAnchor() <= 0 ? 2 : request.getMaxPerAnchor();
        int maxChars = request.getMaxChars() <= 0 ? 2200 : request.getMaxChars();

        List<Long> allowedDocumentIds = normalizeLongs(request.getDocumentIds());
        List<Long> allowedTaskIds = normalizeLongs(request.getTaskIds());
        List<Long> allowedKnowledgeBaseIds = normalizeLongs(request.getKnowledgeBaseIds());
        if (allowedDocumentIds.isEmpty() || allowedTaskIds.isEmpty()) {
            return List.of();
        }

        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = listDescriptorMap(allowedDocumentIds).values().stream()
            .filter(descriptor -> descriptor != null && descriptor.getDocumentId() != null)
            .filter(descriptor -> allowedKnowledgeBaseIds.isEmpty()
                || descriptor.getKnowledgeBaseId() != null && allowedKnowledgeBaseIds.contains(descriptor.getKnowledgeBaseId()))
            .collect(Collectors.toMap(
                KnowledgeDocumentDescriptor::getDocumentId,
                descriptor -> descriptor,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        if (descriptorMap.isEmpty()) {
            return List.of();
        }

        List<StructureAnchorProbe> probes = buildStructureAnchorProbes(request);
        if (probes.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, RetrievalDocument> expanded = new LinkedHashMap<>();
        addOrderedSourceHeadings(
            expanded,
            request.getSourceHeadingNodeIds(),
            descriptorMap,
            allowedDocumentIds,
            allowedTaskIds,
            maxTotal,
            maxChars
        );
        for (StructureAnchorProbe probe : probes) {
            if (expanded.size() >= maxTotal) {
                break;
            }
            List<RetrievalDocument> documents = findBodyEvidenceForProbe(
                probe,
                descriptorMap,
                allowedDocumentIds,
                allowedTaskIds,
                maxPerAnchor,
                maxChars
            );
            for (RetrievalDocument document : documents) {
                if (expanded.size() >= maxTotal) {
                    break;
                }
                expanded.putIfAbsent(structureEvidenceIdentity(document), document);
            }
        }
        return new ArrayList<>(expanded.values());
    }

    private void addOrderedSourceHeadings(Map<String, RetrievalDocument> expanded,
                                          List<Long> structureNodeIds,
                                          Map<Long, KnowledgeDocumentDescriptor> descriptorMap,
                                          List<Long> allowedDocumentIds,
                                          List<Long> allowedTaskIds,
                                          int maxTotal,
                                          int maxChars) {
        List<Long> orderedNodeIds = normalizeLongs(structureNodeIds);
        if (orderedNodeIds.isEmpty() || expanded.size() >= maxTotal) {
            return;
        }
        List<SuperAgentDocumentChunk> candidates = documentChunkMapper.selectList(
            baseChunkWrapper(allowedDocumentIds, allowedTaskIds)
                .in(SuperAgentDocumentChunk::getStructureNodeId, orderedNodeIds)
                .eq(SuperAgentDocumentChunk::getChunkType, "TITLE")
                .orderByAsc(SuperAgentDocumentChunk::getChunkNo)
        );
        Map<Long, SuperAgentDocumentChunk> headingByNodeId = candidates.stream()
            .filter(chunk -> isOriginalSourceHeading(chunk, orderedNodeIds, allowedDocumentIds, allowedTaskIds))
            .sorted(Comparator.comparing(
                SuperAgentDocumentChunk::getChunkNo,
                Comparator.nullsLast(Integer::compareTo)
            ))
            .collect(Collectors.toMap(
                SuperAgentDocumentChunk::getStructureNodeId,
                chunk -> chunk,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        for (Long structureNodeId : orderedNodeIds) {
            if (expanded.size() >= maxTotal) {
                break;
            }
            SuperAgentDocumentChunk chunk = headingByNodeId.get(structureNodeId);
            KnowledgeDocumentDescriptor descriptor = chunk == null ? null : descriptorMap.get(chunk.getDocumentId());
            if (chunk == null || descriptor == null) {
                continue;
            }
            StructureAnchorProbe probe = new StructureAnchorProbe(
                structureNodeId,
                safeText(chunk.getCanonicalPath()),
                safeText(chunk.getSectionPath()),
                MATCH_NODE_ID
            );
            RetrievalDocument heading = buildStructureAnchorChunkEvidence(
                chunk,
                null,
                descriptor,
                probe,
                BODY_RESOLVED_NODE_TITLE,
                BODY_KIND_SOURCE_HEADING,
                maxChars,
                false
            );
            expanded.putIfAbsent(structureEvidenceIdentity(heading), heading);
        }
    }

    private boolean isOriginalSourceHeading(SuperAgentDocumentChunk chunk,
                                            List<Long> orderedNodeIds,
                                            List<Long> allowedDocumentIds,
                                            List<Long> allowedTaskIds) {
        return chunk != null
            && Objects.equals(chunk.getSourceType(), DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
            && "TITLE".equalsIgnoreCase(safeText(chunk.getChunkType()))
            && StrUtil.isNotBlank(chunk.getChunkText())
            && chunk.getStructureNodeId() != null
            && orderedNodeIds.contains(chunk.getStructureNodeId())
            && chunk.getDocumentId() != null
            && allowedDocumentIds.contains(chunk.getDocumentId())
            && chunk.getTaskId() != null
            && allowedTaskIds.contains(chunk.getTaskId());
    }

    private List<StructureAnchorProbe> buildStructureAnchorProbes(StructureAnchoredEvidenceRequest request) {
        LinkedHashMap<String, StructureAnchorProbe> probes = new LinkedHashMap<>();
        for (Long structureNodeId : normalizeLongs(request.getStructureNodeIds())) {
            probes.putIfAbsent("node:" + structureNodeId,
                new StructureAnchorProbe(structureNodeId, "", "", MATCH_NODE_ID));
        }
        for (String canonicalPath : normalizeTexts(request.getCanonicalPaths())) {
            probes.putIfAbsent("canonical:" + normalizeAnchor(canonicalPath),
                new StructureAnchorProbe(null, canonicalPath, "", MATCH_CANONICAL_EXACT));
        }
        for (String sectionAnchor : normalizeTexts(request.getSectionAnchors())) {
            probes.putIfAbsent("section:" + normalizeAnchor(sectionAnchor),
                new StructureAnchorProbe(null, "", sectionAnchor, MATCH_TITLE_SAME_SECTION));
        }
        if (request.getCandidateDocuments() != null) {
            for (RetrievalDocument document : request.getCandidateDocuments()) {
                if (document == null || document.getMetadata() == null) {
                    continue;
                }
                Long structureNodeId = asLong(document.getMetadata().get(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID));
                String canonicalPath = asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH));
                String sectionPath = asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.SECTION_PATH));
                if (structureNodeId != null) {
                    probes.putIfAbsent("candidate-node:" + structureNodeId,
                        new StructureAnchorProbe(structureNodeId, canonicalPath, sectionPath, MATCH_NODE_ID));
                    continue;
                }
                if (StrUtil.isNotBlank(canonicalPath)) {
                    probes.putIfAbsent("candidate-canonical:" + normalizeAnchor(canonicalPath),
                        new StructureAnchorProbe(null, canonicalPath, sectionPath, MATCH_CANONICAL_EXACT));
                }
                else if (StrUtil.isNotBlank(sectionPath)) {
                    probes.putIfAbsent("candidate-section:" + normalizeAnchor(sectionPath),
                        new StructureAnchorProbe(null, "", sectionPath, MATCH_TITLE_SAME_SECTION));
                }
            }
        }
        return new ArrayList<>(probes.values());
    }

    private List<RetrievalDocument> findBodyEvidenceForProbe(StructureAnchorProbe probe,
                                                    Map<Long, KnowledgeDocumentDescriptor> descriptorMap,
                                                    List<Long> allowedDocumentIds,
                                                    List<Long> allowedTaskIds,
                                                    int maxPerAnchor,
                                                    int maxChars) {
        List<SuperAgentDocumentParentBlock> parentBlocks = queryParentBlocks(probe, allowedDocumentIds, allowedTaskIds, maxPerAnchor);
        if (parentBlocks.isEmpty()) {
            return List.of();
        }
        List<RetrievalDocument> bodyEvidence = new ArrayList<>();
        for (SuperAgentDocumentParentBlock parentBlock : parentBlocks) {
            KnowledgeDocumentDescriptor descriptor = descriptorMap.get(parentBlock.getDocumentId());
            if (descriptor == null) {
                continue;
            }
            bodyEvidence.addAll(resolveRawBodyEvidence(parentBlock, descriptor, probe, allowedDocumentIds, allowedTaskIds, maxPerAnchor, maxChars));
            if (bodyEvidence.size() >= maxPerAnchor) {
                break;
            }
        }
        return bodyEvidence.stream()
            .limit(Math.max(1, maxPerAnchor))
            .toList();
    }

    private List<SuperAgentDocumentParentBlock> queryParentBlocks(StructureAnchorProbe probe,
                                                                  List<Long> allowedDocumentIds,
                                                                  List<Long> allowedTaskIds,
                                                                  int maxPerAnchor) {
        LambdaQueryWrapper<SuperAgentDocumentParentBlock> exactWrapper = baseParentBlockWrapper(allowedDocumentIds, allowedTaskIds);
        boolean hasExact = false;
        if (probe.structureNodeId() != null) {
            exactWrapper.eq(SuperAgentDocumentParentBlock::getStructureNodeId, probe.structureNodeId());
            hasExact = true;
        }
        else if (StrUtil.isNotBlank(probe.canonicalPath())) {
            exactWrapper.eq(SuperAgentDocumentParentBlock::getCanonicalPath, probe.canonicalPath().trim());
            hasExact = true;
        }
        else if (StrUtil.isNotBlank(probe.sectionPath())) {
            exactWrapper.eq(SuperAgentDocumentParentBlock::getSectionPath, probe.sectionPath().trim());
            hasExact = true;
        }
        if (hasExact) {
            List<SuperAgentDocumentParentBlock> exact = parentBlockMapper.selectList(exactWrapper
                .orderByAsc(SuperAgentDocumentParentBlock::getParentNo)
                .last("LIMIT " + Math.max(1, maxPerAnchor)));
            if (!exact.isEmpty()) {
                return exact;
            }
        }

        String descendantPath = StrUtil.blankToDefault(probe.canonicalPath(), "");
        if (StrUtil.isBlank(descendantPath)) {
            descendantPath = StrUtil.blankToDefault(probe.sectionPath(), "");
        }
        if (StrUtil.isBlank(descendantPath)) {
            return List.of();
        }
        return parentBlockMapper.selectList(baseParentBlockWrapper(allowedDocumentIds, allowedTaskIds)
            .likeRight(SuperAgentDocumentParentBlock::getCanonicalPath, descendantPath.trim() + "/")
            .orderByAsc(SuperAgentDocumentParentBlock::getCanonicalPath)
            .orderByAsc(SuperAgentDocumentParentBlock::getParentNo)
            .last("LIMIT " + Math.max(1, maxPerAnchor)));
    }

    private LambdaQueryWrapper<SuperAgentDocumentParentBlock> baseParentBlockWrapper(List<Long> allowedDocumentIds,
                                                                                    List<Long> allowedTaskIds) {
        return new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
            .in(SuperAgentDocumentParentBlock::getDocumentId, allowedDocumentIds)
            .in(SuperAgentDocumentParentBlock::getTaskId, allowedTaskIds)
            .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode());
    }

    private List<RetrievalDocument> resolveRawBodyEvidence(SuperAgentDocumentParentBlock anchorParent,
                                                  KnowledgeDocumentDescriptor descriptor,
                                                  StructureAnchorProbe probe,
                                                  List<Long> allowedDocumentIds,
                                                  List<Long> allowedTaskIds,
                                                  int maxPerAnchor,
                                                  int maxChars) {
        LinkedHashMap<String, RetrievalDocument> resolved = new LinkedHashMap<>();
        addChunkEvidence(resolved, queryNodeTextChunks(anchorParent, allowedDocumentIds, allowedTaskIds),
            anchorParent, descriptor, probe, BODY_RESOLVED_NODE_TEXT, BODY_KIND_TEXT_CHUNK, maxChars);
        addChunkEvidence(resolved, queryParentTextChunks(anchorParent, allowedDocumentIds, allowedTaskIds),
            anchorParent, descriptor, probe, BODY_RESOLVED_PARENT_TEXT, BODY_KIND_TEXT_CHUNK, maxChars);
        if (resolved.isEmpty()) {
            addChunkEvidence(resolved, queryContinuationListChunks(anchorParent, allowedDocumentIds, allowedTaskIds, Math.max(2, maxPerAnchor)),
                anchorParent, descriptor, probe, BODY_RESOLVED_CONTINUATION_LIST, BODY_KIND_LIST_CONTINUATION, maxChars);
        }
        if (resolved.size() < maxPerAnchor) {
            addChunkEvidence(resolved, queryDirectChildTextChunks(anchorParent, allowedDocumentIds, allowedTaskIds, maxPerAnchor),
                anchorParent, descriptor, probe, BODY_RESOLVED_DIRECT_CHILD, BODY_KIND_CHILD_SECTION, maxChars);
        }
        if (resolved.size() < maxPerAnchor) {
            addChunkEvidence(resolved, queryDescendantTextChunks(anchorParent, allowedDocumentIds, allowedTaskIds, maxPerAnchor),
                anchorParent, descriptor, probe, BODY_RESOLVED_DESCENDANT, BODY_KIND_TEXT_CHUNK, maxChars);
        }
        return resolved.values().stream()
            .limit(Math.max(1, maxPerAnchor))
            .toList();
    }

    private void addChunkEvidence(Map<String, RetrievalDocument> target,
                                  List<SuperAgentDocumentChunk> chunks,
                                  SuperAgentDocumentParentBlock anchorParent,
                                  KnowledgeDocumentDescriptor descriptor,
                                  StructureAnchorProbe probe,
                                  String resolvedFrom,
                                  String candidateKind,
                                  int maxChars) {
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        for (SuperAgentDocumentChunk chunk : chunks) {
            if (!isRawBodyChunk(chunk, BODY_RESOLVED_CONTINUATION_LIST.equals(resolvedFrom))) {
                continue;
            }
            RetrievalDocument evidence = buildStructureAnchorChunkEvidence(chunk, anchorParent, descriptor, probe, resolvedFrom, candidateKind, maxChars);
            target.putIfAbsent(structureEvidenceIdentity(evidence), evidence);
        }
    }

    private List<SuperAgentDocumentChunk> queryNodeTextChunks(SuperAgentDocumentParentBlock anchorParent,
                                                             List<Long> allowedDocumentIds,
                                                             List<Long> allowedTaskIds) {
        if (anchorParent.getStructureNodeId() == null) {
            return List.of();
        }
        return documentChunkMapper.selectList(baseChunkWrapper(allowedDocumentIds, allowedTaskIds)
            .eq(SuperAgentDocumentChunk::getStructureNodeId, anchorParent.getStructureNodeId())
            .eq(SuperAgentDocumentChunk::getChunkType, "TEXT")
            .orderByAsc(SuperAgentDocumentChunk::getChunkNo)
            .last("LIMIT 4"));
    }

    private List<SuperAgentDocumentChunk> queryParentTextChunks(SuperAgentDocumentParentBlock anchorParent,
                                                               List<Long> allowedDocumentIds,
                                                               List<Long> allowedTaskIds) {
        if (anchorParent.getId() == null) {
            return List.of();
        }
        return documentChunkMapper.selectList(baseChunkWrapper(allowedDocumentIds, allowedTaskIds)
            .eq(SuperAgentDocumentChunk::getParentBlockId, anchorParent.getId())
            .eq(SuperAgentDocumentChunk::getChunkType, "TEXT")
            .orderByAsc(SuperAgentDocumentChunk::getChunkNo)
            .last("LIMIT 4"));
    }

    private List<SuperAgentDocumentChunk> queryContinuationListChunks(SuperAgentDocumentParentBlock anchorParent,
                                                                     List<Long> allowedDocumentIds,
                                                                     List<Long> allowedTaskIds,
                                                                     int limit) {
        Integer anchorNo = anchorParent.getParentNo();
        if (anchorNo == null) {
            return List.of();
        }
        List<SuperAgentDocumentChunk> nextChunks = documentChunkMapper.selectList(baseChunkWrapper(allowedDocumentIds, allowedTaskIds)
            .eq(SuperAgentDocumentChunk::getDocumentId, anchorParent.getDocumentId())
            .eq(SuperAgentDocumentChunk::getTaskId, anchorParent.getTaskId())
            .gt(SuperAgentDocumentChunk::getParentBlockId, anchorParent.getId())
            .orderByAsc(SuperAgentDocumentChunk::getChunkNo)
            .last("LIMIT " + Math.max(2, limit * 2)));
        return nextChunks.stream()
            .filter(this::isListContinuationChunk)
            .limit(Math.max(1, limit))
            .toList();
    }

    private List<SuperAgentDocumentChunk> queryDirectChildTextChunks(SuperAgentDocumentParentBlock anchorParent,
                                                                    List<Long> allowedDocumentIds,
                                                                    List<Long> allowedTaskIds,
                                                                    int limit) {
        String canonicalPath = normalizeAnchor(anchorParent.getCanonicalPath());
        if (StrUtil.isBlank(canonicalPath)) {
            return List.of();
        }
        String childPrefix = canonicalPath + "/";
        return documentChunkMapper.selectList(baseChunkWrapper(allowedDocumentIds, allowedTaskIds)
            .likeRight(SuperAgentDocumentChunk::getCanonicalPath, childPrefix)
            .and(wrapper -> wrapper.eq(SuperAgentDocumentChunk::getChunkType, "TEXT")
                .or()
                .eq(SuperAgentDocumentChunk::getChunkType, "LIST"))
            .orderByAsc(SuperAgentDocumentChunk::getCanonicalPath)
            .orderByAsc(SuperAgentDocumentChunk::getChunkNo)
            .last("LIMIT " + Math.max(1, limit * 2))).stream()
            .filter(chunk -> isDirectCanonicalChild(canonicalPath, chunk.getCanonicalPath()))
            .limit(Math.max(1, limit))
            .toList();
    }

    private List<SuperAgentDocumentChunk> queryDescendantTextChunks(SuperAgentDocumentParentBlock anchorParent,
                                                                   List<Long> allowedDocumentIds,
                                                                   List<Long> allowedTaskIds,
                                                                   int limit) {
        String canonicalPath = normalizeAnchor(anchorParent.getCanonicalPath());
        if (StrUtil.isBlank(canonicalPath)) {
            return List.of();
        }
        return documentChunkMapper.selectList(baseChunkWrapper(allowedDocumentIds, allowedTaskIds)
            .likeRight(SuperAgentDocumentChunk::getCanonicalPath, canonicalPath + "/")
            .and(wrapper -> wrapper.eq(SuperAgentDocumentChunk::getChunkType, "TEXT")
                .or()
                .eq(SuperAgentDocumentChunk::getChunkType, "LIST"))
            .orderByAsc(SuperAgentDocumentChunk::getCanonicalPath)
            .orderByAsc(SuperAgentDocumentChunk::getChunkNo)
            .last("LIMIT " + Math.max(1, limit)));
    }

    private LambdaQueryWrapper<SuperAgentDocumentChunk> baseChunkWrapper(List<Long> allowedDocumentIds,
                                                                        List<Long> allowedTaskIds) {
        return new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .in(SuperAgentDocumentChunk::getDocumentId, allowedDocumentIds)
            .in(SuperAgentDocumentChunk::getTaskId, allowedTaskIds)
            .eq(SuperAgentDocumentChunk::getSourceType, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode());
    }

    private RetrievalDocument buildStructureAnchorChunkEvidence(SuperAgentDocumentChunk chunk,
                                                       SuperAgentDocumentParentBlock anchorParent,
                                                       KnowledgeDocumentDescriptor descriptor,
                                                       StructureAnchorProbe probe,
                                                       String resolvedFrom,
                                                       String candidateKind,
                                                       int maxChars) {
        return buildStructureAnchorChunkEvidence(
            chunk,
            anchorParent,
            descriptor,
            probe,
            resolvedFrom,
            candidateKind,
            maxChars,
            true
        );
    }

    private RetrievalDocument buildStructureAnchorChunkEvidence(SuperAgentDocumentChunk chunk,
                                                       SuperAgentDocumentParentBlock anchorParent,
                                                       KnowledgeDocumentDescriptor descriptor,
                                                       StructureAnchorProbe probe,
                                                       String resolvedFrom,
                                                       String candidateKind,
                                                       int maxChars,
                                                       boolean rawBody) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, STRUCTURE_ANCHOR_CHANNEL);
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, 1D);
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, chunk.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, descriptor == null ? "" : safeText(descriptor.getDocumentName()));
        if (descriptor != null) {
            putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, descriptor.getKnowledgeBaseId());
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, safeText(descriptor.getKnowledgeBaseName()));
        }
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, chunk.getTaskId());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, chunk.getParentBlockId());
        if (anchorParent != null) {
            putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO, anchorParent.getParentNo());
        }
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, chunk.getId());
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, chunk.getChunkNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(chunk.getSectionPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, chunk.getStructureNodeId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE, chunk.getStructureNodeType());
        metadata.put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, safeText(chunk.getCanonicalPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.ITEM_INDEX, chunk.getItemIndex());
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, normalizeBodyChunkType(chunk));
        metadata.put(DocumentKnowledgeMetadataKeys.CONTENT_WITH_WEIGHT, safeText(chunk.getContentWithWeight()));
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, safeText(chunk.getTitle()));
        metadata.put(DocumentKnowledgeMetadataKeys.KEYWORDS, safeText(chunk.getKeywords()));
        metadata.put(DocumentKnowledgeMetadataKeys.QUESTIONS, safeText(chunk.getQuestions()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, chunk.getPageNo());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, safeText(chunk.getPageRange()));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, safeText(chunk.getBboxJson()));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, safeText(chunk.getSourceBlockIds()));
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, safeText(chunk.getChunkText()));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_STRUCTURE_ANCHOR, probe.sourceAnchor());
        metadata.put(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_MATCH_TYPE, resolveAnchorMatchType(chunk, anchorParent, probe));
        metadata.put(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_RAW_BODY, rawBody);
        if (!rawBody && BODY_KIND_SOURCE_HEADING.equals(candidateKind)) {
            metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_AUTHORED_HEADING, true);
            metadata.put(DocumentKnowledgeMetadataKeys.STRUCTURE_NAVIGATION_REQUIRED_SOURCE, true);
        }
        metadata.put(DocumentKnowledgeMetadataKeys.STRUCTURE_BODY_RESOLVED_FROM, resolvedFrom);
        metadata.put(DocumentKnowledgeMetadataKeys.STRUCTURE_BODY_CANDIDATE_KIND, candidateKind);

        return RetrievalDocument.builder()
            .id("structure-chunk-" + chunk.getId())
            .text(trimText(safeText(chunk.getChunkText()), maxChars))
            .metadata(metadata)
            .score(1D)
            .build();
    }

    private boolean isRawBodyChunk(SuperAgentDocumentChunk chunk, boolean allowListContinuation) {
        if (chunk == null || chunk.getSourceType() == null
            || !Objects.equals(chunk.getSourceType(), DocumentChunkSourceTypeEnum.ORIGINAL.getCode())) {
            return false;
        }
        String chunkType = asText(chunk.getChunkType());
        if ("TEXT".equalsIgnoreCase(chunkType) || "LIST".equalsIgnoreCase(chunkType) || "TABLE".equalsIgnoreCase(chunkType)) {
            return hasBodyText(chunk.getChunkText()) && !isTitleOnlyText(chunk.getChunkText());
        }
        return allowListContinuation && isListContinuationChunk(chunk);
    }

    private boolean isListContinuationChunk(SuperAgentDocumentChunk chunk) {
        return chunk != null
            && hasBodyText(chunk.getChunkText())
            && containsMultipleOrderedItems(chunk.getChunkText())
            && !"GRAPH_RAG".equalsIgnoreCase(asText(chunk.getChunkType()));
    }

    private boolean hasBodyText(String text) {
        return safeText(text).replace("#", "").trim().length() >= 12;
    }

    private boolean isTitleOnlyText(String text) {
        String normalized = safeText(text).trim();
        if (normalized.isBlank()) {
            return true;
        }
        String withoutHashes = normalized.replaceAll("^#{1,6}\\s*", "").trim();
        return normalized.length() <= 120
            && (normalized.startsWith("#")
            || withoutHashes.matches("^\\d+(?:\\.\\d+){1,5}\\s+\\S[^。；;!?！？]*$"));
    }

    private boolean containsMultipleOrderedItems(String text) {
        String normalized = safeText(text).replace('\n', ' ');
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?:^|\\s)(\\d{1,2})[、.]\\s+")
            .matcher(normalized);
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count >= 2) {
                return true;
            }
        }
        return false;
    }

    private boolean isDirectCanonicalChild(String parentCanonicalPath, String candidateCanonicalPath) {
        String parent = normalizeAnchor(parentCanonicalPath);
        String candidate = normalizeAnchor(candidateCanonicalPath);
        if (parent.isBlank() || !candidate.startsWith(parent + "/")) {
            return false;
        }
        String remainder = candidate.substring(parent.length() + 1);
        return !remainder.isBlank() && !remainder.contains("/");
    }

    private String normalizeBodyChunkType(SuperAgentDocumentChunk chunk) {
        if (chunk == null) {
            return "TEXT";
        }
        if (isListContinuationChunk(chunk)) {
            return "LIST";
        }
        String chunkType = safeText(chunk.getChunkType());
        return StrUtil.isBlank(chunkType) ? "TEXT" : chunkType;
    }

    private String resolveAnchorMatchType(SuperAgentDocumentChunk chunk,
                                          SuperAgentDocumentParentBlock anchorParent,
                                          StructureAnchorProbe probe) {
        if (probe.structureNodeId() != null && Objects.equals(probe.structureNodeId(), chunk.getStructureNodeId())) {
            return MATCH_NODE_ID;
        }
        String canonicalPath = normalizeAnchor(chunk.getCanonicalPath());
        String probeCanonical = normalizeAnchor(probe.canonicalPath());
        if (StrUtil.isNotBlank(probeCanonical)) {
            if (canonicalPath.equals(probeCanonical)) {
                return MATCH_CANONICAL_EXACT;
            }
            if (canonicalPath.startsWith(probeCanonical + "/")) {
                return MATCH_CANONICAL_DESCENDANT;
            }
        }
        if (anchorParent != null && !Objects.equals(anchorParent.getStructureNodeId(), chunk.getStructureNodeId())) {
            return MATCH_CANONICAL_DESCENDANT;
        }
        return MATCH_TITLE_SAME_SECTION;
    }

    private String resolveAnchorMatchType(SuperAgentDocumentParentBlock parentBlock, StructureAnchorProbe probe) {
        if (probe.structureNodeId() != null && Objects.equals(probe.structureNodeId(), parentBlock.getStructureNodeId())) {
            return MATCH_NODE_ID;
        }
        String canonicalPath = normalizeAnchor(parentBlock.getCanonicalPath());
        String probeCanonical = normalizeAnchor(probe.canonicalPath());
        if (StrUtil.isNotBlank(probeCanonical)) {
            if (canonicalPath.equals(probeCanonical)) {
                return MATCH_CANONICAL_EXACT;
            }
            if (canonicalPath.startsWith(probeCanonical + "/")) {
                return MATCH_CANONICAL_DESCENDANT;
            }
        }
        return MATCH_TITLE_SAME_SECTION;
    }

    private String structureEvidenceIdentity(RetrievalDocument document) {
        if (document == null || document.getMetadata() == null) {
            return "";
        }
        String candidateKind = asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.STRUCTURE_BODY_CANDIDATE_KIND));
        Long chunkId = asLong(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_ID));
        if (BODY_KIND_SOURCE_HEADING.equals(candidateKind) && chunkId != null) {
            return "heading-chunk:" + chunkId;
        }
        Long parentBlockId = asLong(document.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
        if (parentBlockId != null) {
            return "parent:" + parentBlockId;
        }
        return "document:" + asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID))
            + ":section:" + normalizeAnchor(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH));
    }

    private RetrievalDocument buildRetrievedDocument(long chunkId,
                                            String chunkText,
                                            String contentWithWeight,
                                            String chunkType,
                                            String title,
                                            String keywords,
                                            String questions,
                                            long taskId,
                                            long parentBlockId,
                                            int chunkNo,
                                            String sectionPath,
                                            Long structureNodeId,
                                            Integer structureNodeType,
                                            String canonicalPath,
                                            Integer itemIndex,
                                            Integer pageNo,
                                            String pageRange,
                                            String bboxJson,
                                            String sourceBlockIds,
                                            KnowledgeDocumentDescriptor descriptor,
                                            String channel,
                                            double score) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channel);
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, chunkId);
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, taskId);
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, parentBlockId);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, chunkNo);
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(sectionPath));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, structureNodeId);
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE, structureNodeType);
        metadata.put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, safeText(canonicalPath));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.ITEM_INDEX, itemIndex);
        metadata.put(DocumentKnowledgeMetadataKeys.CONTENT_WITH_WEIGHT, safeText(contentWithWeight));
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, safeText(chunkType));
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, safeText(title));
        metadata.put(DocumentKnowledgeMetadataKeys.KEYWORDS, safeText(keywords));
        metadata.put(DocumentKnowledgeMetadataKeys.QUESTIONS, safeText(questions));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, pageNo);
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, safeText(pageRange));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, safeText(bboxJson));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, safeText(sourceBlockIds));
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, chunkText);
        if (descriptor != null) {

            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, descriptor.getDocumentId());
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, safeText(descriptor.getDocumentName()));
            putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, descriptor.getKnowledgeBaseId());
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, safeText(descriptor.getKnowledgeBaseName()));
        }
        graphRagTypedChunkMetadataSupport.enrichMetadata(metadata, chunkType, sourceBlockIds);

        return RetrievalDocument.builder()
            .id(String.valueOf(chunkId))
            .text(chunkText)
            .metadata(metadata)
            .score(score)
            .build();
    }

    private boolean isSearchableRequest(DocumentRetrieveRequest request) {

        if (request == null || StrUtil.isBlank(request.getQuestion()) || StrUtil.isBlank(request.getRetrievalQuery())) {
            return false;
        }
        return !request.resolvedDocumentIds().isEmpty() && !request.resolvedTaskIds().isEmpty();
    }

    private Map<Long, KnowledgeDocumentDescriptor> listDescriptorMap(List<Long> requestedDocumentIds) {
        List<KnowledgeDocumentDescriptor> descriptors = listRetrievableDocuments();
        if (descriptors.isEmpty()) {
            return Map.of();
        }

        return descriptors.stream()
            .filter(descriptor -> requestedDocumentIds.contains(descriptor.getDocumentId()))
            .collect(Collectors.toMap(
                KnowledgeDocumentDescriptor::getDocumentId,
                descriptor -> descriptor,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private ResolvedMetadataScope resolveMetadataScope(DocumentRetrieveRequest request) {
        List<Long> baseDocumentIds = request.resolvedDocumentIds();
        List<Long> baseTaskIds = request.resolvedTaskIds();
        return new ResolvedMetadataScope(baseDocumentIds, baseTaskIds, request.getFilters());
    }

    private void appendSectionFilters(StringBuilder sqlBuilder, DocumentRetrieveFilters filters) {
        boolean hasSectionHints = filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints());
        if (!hasSectionHints) {
            appendStructureFilters(sqlBuilder, filters);
            return;
        }

        sqlBuilder.append("\n  AND (");
        for (int index = 0; index < filters.getSectionPathHints().size(); index++) {
            if (index > 0) {
                sqlBuilder.append(" OR ");
            }
            sqlBuilder.append("LOWER(COALESCE(section_path, '')) LIKE ?");
        }
        sqlBuilder.append(")");
        appendStructureFilters(sqlBuilder, filters);
    }

    private void appendSectionFilterParams(List<Object> params, DocumentRetrieveFilters filters) {
        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
            for (String sectionHint : filters.getSectionPathHints()) {
                params.add("%" + sectionHint.toLowerCase(Locale.ROOT) + "%");
            }
        }
        appendStructureFilterParams(params, filters);
    }

    private void appendStructureFilters(StringBuilder sqlBuilder, DocumentRetrieveFilters filters) {
        boolean hasStructureNodeIds = filters != null && CollUtil.isNotEmpty(filters.getStructureNodeIdHints());
        boolean hasCanonicalPathHints = filters != null && CollUtil.isNotEmpty(filters.getCanonicalPathHints());
        boolean hasItemIndexes = filters != null && CollUtil.isNotEmpty(filters.getItemIndexHints());
        if (!hasStructureNodeIds && !hasCanonicalPathHints && !hasItemIndexes) {
            return;
        }
        if (hasStructureNodeIds) {
            sqlBuilder.append("\n  AND structure_node_id IN (")
                .append(buildPlaceholders(filters.getStructureNodeIdHints().size()))
                .append(")");
        }
        if (hasCanonicalPathHints) {
            sqlBuilder.append("\n  AND (");
            for (int index = 0; index < filters.getCanonicalPathHints().size(); index++) {
                if (index > 0) {
                    sqlBuilder.append(" OR ");
                }
                sqlBuilder.append("LOWER(COALESCE(canonical_path, '')) LIKE ?");
            }
            sqlBuilder.append(")");
        }
        if (hasItemIndexes) {
            sqlBuilder.append("\n  AND item_index IN (")
                .append(buildPlaceholders(filters.getItemIndexHints().size()))
                .append(")");
        }
        if (filters != null && filters.getUserMetadataEquals() != null && !filters.getUserMetadataEquals().isEmpty()) {
            for (String field : filters.getUserMetadataEquals().keySet()) {
                sqlBuilder.append("\n  AND ").append(org.smartledge.ai.manage.support.IndexRetrievalFilter.userMetadataSqlEquals(field));
            }
        }
    }

    private void appendStructureFilterParams(List<Object> params, DocumentRetrieveFilters filters) {
        if (filters == null) {
            return;
        }
        if (CollUtil.isNotEmpty(filters.getStructureNodeIdHints())) {
            params.addAll(filters.getStructureNodeIdHints());
        }
        if (CollUtil.isNotEmpty(filters.getCanonicalPathHints())) {
            for (String canonicalPathHint : filters.getCanonicalPathHints()) {
                params.add(canonicalPathHint.toLowerCase(Locale.ROOT) + "%");
            }
        }
        if (CollUtil.isNotEmpty(filters.getItemIndexHints())) {
            params.addAll(filters.getItemIndexHints());
        }
        if (filters.getUserMetadataEquals() != null) {
            params.addAll(filters.getUserMetadataEquals().values().stream()
                .map(value -> value == null ? "" : String.valueOf(value))
                .toList());
        }
    }

    private RetrievalDocument buildParentEvidenceDocument(SuperAgentDocumentParentBlock parentBlock,
                                                 List<RetrievalDocument> childDocuments,
                                                 int maxChars) {
        RetrievalDocument bestChild = selectBestChildForParentEvidence(childDocuments);

        double parentScore = aggregateParentScore(childDocuments);
        Map<String, Object> metadata = new LinkedHashMap<>(bestChild.getMetadata());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, parentBlock.getId());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO, parentBlock.getParentNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(parentBlock.getSectionPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, parentBlock.getStructureNodeId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE, parentBlock.getStructureNodeType());
        metadata.put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, safeText(parentBlock.getCanonicalPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.ITEM_INDEX, parentBlock.getItemIndex());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, safeText(parentBlock.getPageRange()));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, safeText(parentBlock.getSourceBlockIds()));
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, parentScore);
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, safeText(parentBlock.getParentText()));
        mergeGraphRagMetadata(metadata, childDocuments);

        LinkedHashSet<String> channels = childDocuments.stream()
            .map(document -> asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHANNEL)))
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL,
            channels.size() > 1 ? "hybrid" : channels.stream().findFirst().orElse("vector"));

        return RetrievalDocument.builder()
            .id("parent-" + parentBlock.getId())
            .text(renderParentEvidenceText(parentBlock, childDocuments, maxChars))
            .metadata(metadata)
            .score(parentScore)
            .build();
    }

    private RetrievalDocument selectBestChildForParentEvidence(List<RetrievalDocument> childDocuments) {
        RetrievalDocument bestByScore = childDocuments.stream()
            .max(Comparator.comparingDouble(document -> {
                Double score = resolveScore(document);
                return score == null ? 0D : score;
            }))
            .orElseThrow();
        Double bestScore = resolveScore(bestByScore);
        if (bestScore == null || bestScore <= 0D) {
            return bestByScore;
        }

        RetrievalDocument bestRelationChild = childDocuments.stream()
            .filter(document -> document != null && document.getMetadata() != null)
            .filter(document -> document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null)
            .max(Comparator.comparingDouble(document -> {
                Double score = resolveScore(document);
                double relationBonus = 0.22D;
                double groupBonus = document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT) instanceof Number number
                    ? Math.min(0.18D, Math.max(0D, number.doubleValue() - 1D) * 0.06D)
                    : 0D;
                return (score == null ? 0D : score) + relationBonus + groupBonus;
            }))
            .orElse(null);
        if (bestRelationChild == null) {
            return bestByScore;
        }

        Double relationScore = resolveScore(bestRelationChild);
        if (relationScore == null) {
            return bestByScore;
        }
        if (relationScore >= bestScore * 0.65D) {
            return bestRelationChild;
        }
        return bestByScore;
    }

    private void mergeGraphRagMetadata(Map<String, Object> metadata, List<RetrievalDocument> childDocuments) {
        RetrievalDocument graphRagChild = selectBestGraphRagChild(childDocuments);
        if (graphRagChild == null || graphRagChild.getMetadata() == null) {
            return;
        }
        Map<String, Object> graphMetadata = graphRagChild.getMetadata();
        for (String key : DocumentKnowledgeMetadataKeys.GRAPH_RAG_METADATA_KEYS) {
            copyIfPresent(graphMetadata, metadata, key);
        }
    }

    private RetrievalDocument selectBestGraphRagChild(List<RetrievalDocument> childDocuments) {
        if (CollUtil.isEmpty(childDocuments)) {
            return null;
        }
        return childDocuments.stream()
            .filter(document -> document != null && document.getMetadata() != null)
            .filter(document -> hasGraphRagMetadata(document.getMetadata()))
            .max(Comparator.comparingDouble(this::graphRagMetadataPriority))
            .orElse(null);
    }

    private boolean hasGraphRagMetadata(Map<String, Object> metadata) {
        String channel = asText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL));
        String sourceType = asText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        return "graph-rag".equalsIgnoreCase(channel)
            || "GRAPH_RAG".equalsIgnoreCase(sourceType)
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID) != null
            || StrUtil.isNotBlank(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY)))
            || StrUtil.isNotBlank(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY)))
            || StrUtil.isNotBlank(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY)))
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID) != null;
    }

    private double graphRagMetadataPriority(RetrievalDocument document) {
        return EvidenceMetadataPriority.forGraphMetadata(document, resolveScoreOrZero(document));
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private double aggregateParentScore(List<RetrievalDocument> childDocuments) {
        double bestChildScore = childDocuments.stream()
            .map(this::resolveScore)
            .filter(Objects::nonNull)
            .max(Double::compareTo)
            .orElse(0D);
        int supportCount = Math.max(0, childDocuments.size() - 1);
        LinkedHashSet<String> channels = childDocuments.stream()
            .map(document -> asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHANNEL)))
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        double supportWeight = Math.min(0.36D, supportCount * 0.12D);
        double multiChannelWeight = channels.size() > 1 ? 0.10D : 0D;
        return bestChildScore * (1D + supportWeight + multiChannelWeight);
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private int compareEvidenceDocument(RetrievalDocument left, RetrievalDocument right) {
        int scoreCompare = Double.compare(resolveScoreOrZero(right), resolveScoreOrZero(left));
        if (scoreCompare != 0) {
            return scoreCompare;
        }
        Integer leftParentNo = asInteger(left == null ? null : left.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO));
        Integer rightParentNo = asInteger(right == null ? null : right.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO));
        int parentNoCompare = compareNullableInteger(leftParentNo, rightParentNo);
        if (parentNoCompare != 0) {
            return parentNoCompare;
        }
        Integer leftChunkNo = asInteger(left == null ? null : left.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO));
        Integer rightChunkNo = asInteger(right == null ? null : right.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO));
        return compareNullableInteger(leftChunkNo, rightChunkNo);
    }

    private double resolveScoreOrZero(RetrievalDocument document) {
        Double score = resolveScore(document);
        return score == null ? 0D : score;
    }

    private int compareNullableInteger(Integer left, Integer right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return Integer.compare(left, right);
    }

    private String renderParentEvidenceText(SuperAgentDocumentParentBlock parentBlock,
                                            List<RetrievalDocument> childDocuments,
                                            int maxChars) {
        String parentText = safeText(parentBlock.getParentText());
        if (StrUtil.isBlank(parentText)) {
            return childDocuments.isEmpty() ? "" : StrUtil.blankToDefault(childDocuments.get(0).getText(), "");
        }

        StringBuilder hitSummaryBuilder = new StringBuilder();
        for (RetrievalDocument childDocument : childDocuments) {
            if (childDocument == null) {
                continue;
            }
            if (!hitSummaryBuilder.isEmpty()) {
                hitSummaryBuilder.append('\n');
            }
            hitSummaryBuilder.append("- child#")
                .append(asInteger(childDocument.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO)))
                .append("：")
                .append(trimText(safeText(childDocument.getText()), 140));
        }

        String composed = joinSections(
            "[父块内容]\n" + parentText,
            hitSummaryBuilder.isEmpty() ? "" : "[命中子片段]\n" + hitSummaryBuilder
        );
        return trimText(composed, Math.max(1, maxChars));
    }

    private Double resolveScore(RetrievalDocument document) {
        if (document == null) {
            return null;
        }
        Object metadataScore = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        if (metadataScore instanceof Number number) {
            return number.doubleValue();
        }
        return document.getScore();
    }

    private String joinSections(String... sections) {
        List<String> parts = new ArrayList<>();
        for (String section : sections) {
            if (StrUtil.isNotBlank(section)) {
                parts.add(section.trim());
            }
        }
        return String.join("\n\n", parts);
    }

    private Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private String trimText(String text, int maxChars) {
        if (StrUtil.isBlank(text) || text.length() <= maxChars) {
            return StrUtil.blankToDefault(text, "");
        }
        return text.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<Long> normalizeLongs(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private List<String> normalizeTexts(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(this::safeText)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
    }

    private String normalizeAnchor(Object value) {
        return asText(value)
            .trim()
            .replace('\\', '/')
            .replaceAll("\\s+", "")
            .toLowerCase(Locale.ROOT);
    }

    private int resolveTopK(int topK) {

        return topK <= 0 ? 10 : Math.min(topK, 50);
    }

    private EmbeddingPort requireEmbeddingModel() {
        EmbeddingPort embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {

            throw new IllegalStateException("当前未找到可用的向量化模型，无法执行向量检索。");
        }
        return embeddingModel;
    }

    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("问题向量生成失败，无法执行检索。");
        }
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {

            if (index > 0) {
                vectorBuilder.append(',');
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append(']');
        return vectorBuilder.toString();
    }

    private String buildPlaceholders(int size) {
        return IntStream.range(0, size)
            .mapToObj(index -> "?")
            .collect(Collectors.joining(","));
    }

    private int defaultInteger(Integer value) {
        return Objects.requireNonNullElse(value, 0);
    }

    private record ResolvedMetadataScope(
        List<Long> documentIds,
        List<Long> taskIds,
        DocumentRetrieveFilters filters
    ) {
    }

    private record StructureAnchorProbe(Long structureNodeId,
                                        String canonicalPath,
                                        String sectionPath,
                                        String sourceMatchType) {

        private String sourceAnchor() {
            if (structureNodeId != null) {
                return String.valueOf(structureNodeId);
            }
            if (StrUtil.isNotBlank(canonicalPath)) {
                return canonicalPath;
            }
            return StrUtil.blankToDefault(sectionPath, sourceMatchType);
        }
    }
}
