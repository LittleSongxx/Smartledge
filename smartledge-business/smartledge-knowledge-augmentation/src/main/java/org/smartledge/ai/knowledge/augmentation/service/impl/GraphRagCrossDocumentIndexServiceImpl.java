package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCanonicalEntityGroup;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCanonicalEntityMember;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCrossDocumentCommunity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgCrossDocumentCommunityMember;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEntity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEvidence;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelation;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelationGroup;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelationGroupMember;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeTopicNode;
import org.smartledge.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCanonicalEntityGroupMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCanonicalEntityMemberMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCrossDocumentCommunityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgCrossDocumentCommunityMemberMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEntityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEvidenceMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationGroupMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationGroupMemberMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.smartledge.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagCrossDocumentIndexBuildResult;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagCrossDocumentIndexService;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCrossDocumentIndex;
import org.smartledge.ai.knowledge.augmentation.support.GraphRagCrossDocumentIndexSupport;
import org.smartledge.ai.knowledge.augmentation.support.RaptorScopeSupport;
import org.smartledge.enums.BusinessStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.smartledge.ai.manage.support.DerivedRowTenantScope;

@Service
@Slf4j
public class GraphRagCrossDocumentIndexServiceImpl implements GraphRagCrossDocumentIndexService {

    private static final String DERIVED_INDEX_SOURCE_TYPE = "java.cross_document_index.v1";

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentKgEntityMapper entityMapper;
    private final SuperAgentKgRelationMapper relationMapper;
    private final SuperAgentKgEvidenceMapper evidenceMapper;
    private final SuperAgentKgCanonicalEntityGroupMapper canonicalGroupMapper;
    private final SuperAgentKgCanonicalEntityMemberMapper canonicalMemberMapper;
    private final SuperAgentKgRelationGroupMapper relationGroupMapper;
    private final SuperAgentKgRelationGroupMemberMapper relationGroupMemberMapper;
    private final SuperAgentKgCrossDocumentCommunityMapper communityMapper;
    private final SuperAgentKgCrossDocumentCommunityMemberMapper communityMemberMapper;
    private final SuperAgentKnowledgeTopicNodeMapper topicNodeMapper;
    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;
    private final GraphRagCrossDocumentIndexSupport indexSupport;
    private final UidGenerator uidGenerator;
    private final ObjectMapper objectMapper;

    public GraphRagCrossDocumentIndexServiceImpl(SuperAgentDocumentMapper documentMapper,
                                                 SuperAgentKgEntityMapper entityMapper,
                                                 SuperAgentKgRelationMapper relationMapper,
                                                 SuperAgentKgEvidenceMapper evidenceMapper,
                                                 SuperAgentKgCanonicalEntityGroupMapper canonicalGroupMapper,
                                                 SuperAgentKgCanonicalEntityMemberMapper canonicalMemberMapper,
                                                 SuperAgentKgRelationGroupMapper relationGroupMapper,
                                                 SuperAgentKgRelationGroupMemberMapper relationGroupMemberMapper,
                                                 SuperAgentKgCrossDocumentCommunityMapper communityMapper,
                                                 SuperAgentKgCrossDocumentCommunityMemberMapper communityMemberMapper,
                                                 SuperAgentKnowledgeTopicNodeMapper topicNodeMapper,
                                                 SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper,
                                                 GraphRagCrossDocumentIndexSupport indexSupport,
                                                 UidGenerator uidGenerator,
                                                 ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.evidenceMapper = evidenceMapper;
        this.canonicalGroupMapper = canonicalGroupMapper;
        this.canonicalMemberMapper = canonicalMemberMapper;
        this.relationGroupMapper = relationGroupMapper;
        this.relationGroupMemberMapper = relationGroupMemberMapper;
        this.communityMapper = communityMapper;
        this.communityMemberMapper = communityMemberMapper;
        this.topicNodeMapper = topicNodeMapper;
        this.topicDocumentRelationMapper = topicDocumentRelationMapper;
        this.indexSupport = indexSupport;
        this.uidGenerator = uidGenerator;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public synchronized List<GraphRagCrossDocumentIndexBuildResult> rebuildAll() {
        return DerivedRowTenantScope.callCrossTenant(() -> rebuildAllInternal(null, null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public synchronized List<GraphRagCrossDocumentIndexBuildResult> rebuildAll(Long pendingDocumentId,
                                                                               Long pendingTaskId) {
        // 跨文档投影是全局重建（读全库活跃文档、整表重写派生索引），必须显式声明系统上下文：
        // 构建链路的其余部分现在跑在父文档租户作用域内（S22 批次 2），若这里继承那个作用域，
        // 租户拦截器会把 selectActiveForGraphProjection 收窄成本租户，跨租户派生索引会静默只剩一个租户。
        return DerivedRowTenantScope.callCrossTenant(() -> rebuildAllInternal(pendingDocumentId, pendingTaskId));
    }

    private List<GraphRagCrossDocumentIndexBuildResult> rebuildAllInternal(Long pendingDocumentId,
                                                                           Long pendingTaskId) {
        List<SuperAgentDocument> activeDocuments = documentMapper.selectActiveForGraphProjection();
        Map<Long, SuperAgentDocument> documentMap = safeDocuments(activeDocuments);
        Map<Long, Long> activeTaskByDocumentId = activeTaskByDocumentId(documentMap, pendingDocumentId, pendingTaskId);
        List<Long> documentIds = new ArrayList<>(activeTaskByDocumentId.keySet());
        List<SuperAgentKgEntity> entities = filterActiveRows(
            listEntities(documentIds, null),
            activeTaskByDocumentId,
            SuperAgentKgEntity::getDocumentId,
            SuperAgentKgEntity::getTaskId
        );
        List<SuperAgentKgRelation> relations = filterActiveRows(
            listRelations(documentIds, null),
            activeTaskByDocumentId,
            SuperAgentKgRelation::getDocumentId,
            SuperAgentKgRelation::getTaskId
        );
        List<SuperAgentKgEvidence> evidences = filterActiveRows(
            listEvidences(documentIds, null),
            activeTaskByDocumentId,
            SuperAgentKgEvidence::getDocumentId,
            SuperAgentKgEvidence::getTaskId
        );
        if (entities.isEmpty()) {
            deleteAllDerivedIndexes();
            return List.of(GraphRagCrossDocumentIndexBuildResult.builder()
                .scopeKey(GLOBAL_SCOPE_KEY)
                .build());
        }

        LinkedHashMap<String, ScopeDataset> scopes = buildScopes(entities, relations, evidences, documentMap);

        deleteAllDerivedIndexes();
        List<GraphRagCrossDocumentIndexBuildResult> results = new ArrayList<>();
        for (Map.Entry<String, ScopeDataset> entry : scopes.entrySet()) {
            results.add(persistScope(entry.getKey(), entry.getValue()));
        }
        log.info("GraphRAG 跨文档持久化图谱索引重建完成: scopeCount={}, results={}", results.size(), results);
        return results;
    }

    private Map<Long, SuperAgentDocument> safeDocuments(List<SuperAgentDocument> documents) {
        if (CollUtil.isEmpty(documents)) {
            return new LinkedHashMap<>();
        }
        return documents.stream()
            .filter(document -> document != null && document.getId() != null)
            .collect(Collectors.toMap(
                SuperAgentDocument::getId,
                document -> document,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private Map<Long, Long> activeTaskByDocumentId(Map<Long, SuperAgentDocument> documentMap,
                                                   Long pendingDocumentId,
                                                   Long pendingTaskId) {
        Map<Long, Long> activeTasks = new LinkedHashMap<>();
        for (SuperAgentDocument document : documentMap.values()) {
            if (document.getLastIndexTaskId() != null) {
                activeTasks.put(document.getId(), document.getLastIndexTaskId());
            }
        }
        if (pendingDocumentId != null && pendingTaskId != null && documentMap.containsKey(pendingDocumentId)) {
            activeTasks.put(pendingDocumentId, pendingTaskId);
        }
        return activeTasks;
    }

    private <T> List<T> filterActiveRows(List<T> rows,
                                         Map<Long, Long> activeTaskByDocumentId,
                                         java.util.function.Function<T, Long> documentId,
                                         java.util.function.Function<T, Long> taskId) {
        if (CollUtil.isEmpty(rows) || activeTaskByDocumentId.isEmpty()) {
            return List.of();
        }
        return rows.stream()
            .filter(Objects::nonNull)
            .filter(row -> Objects.equals(activeTaskByDocumentId.get(documentId.apply(row)), taskId.apply(row)))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GraphRagCrossDocumentIndex loadIndex(List<Long> documentIds, List<Long> taskIds) {
        if (CollUtil.isEmpty(documentIds)) {
            return GraphRagCrossDocumentIndex.empty();
        }
        String scopeKey = resolveLoadScopeKey(documentIds);
        return loadIndexByScope(scopeKey, documentIds, taskIds);
    }

    private GraphRagCrossDocumentIndex loadIndexByScope(String scopeKey, List<Long> documentIds, List<Long> taskIds) {
        List<SuperAgentKgCanonicalEntityMember> members = canonicalMemberMapper.selectList(new LambdaQueryWrapper<SuperAgentKgCanonicalEntityMember>()
            .eq(SuperAgentKgCanonicalEntityMember::getScopeKey, scopeKey)
            .in(SuperAgentKgCanonicalEntityMember::getDocumentId, documentIds)
            .eq(SuperAgentKgCanonicalEntityMember::getStatus, BusinessStatus.YES.getCode()));
        if (CollUtil.isNotEmpty(taskIds)) {
            Set<Long> taskIdSet = new LinkedHashSet<>(taskIds);
            members = members.stream().filter(member -> taskIdSet.contains(member.getTaskId())).toList();
        }
        if (members.isEmpty()) {
            return GraphRagCrossDocumentIndex.empty();
        }

        Set<Long> groupIds = members.stream()
            .map(SuperAgentKgCanonicalEntityMember::getGroupId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SuperAgentKgCanonicalEntityGroup> groupById = canonicalGroupMapper.selectList(new LambdaQueryWrapper<SuperAgentKgCanonicalEntityGroup>()
                .eq(SuperAgentKgCanonicalEntityGroup::getScopeKey, scopeKey)
                .in(SuperAgentKgCanonicalEntityGroup::getId, groupIds)
                .eq(SuperAgentKgCanonicalEntityGroup::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.toMap(SuperAgentKgCanonicalEntityGroup::getId, item -> item, (left, right) -> left, LinkedHashMap::new));

        LinkedHashMap<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByEntityId = new LinkedHashMap<>();
        Map<String, List<SuperAgentKgCanonicalEntityMember>> membersByGroupKey = members.stream()
            .collect(Collectors.groupingBy(SuperAgentKgCanonicalEntityMember::getGroupKey, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<SuperAgentKgCanonicalEntityMember>> entry : membersByGroupKey.entrySet()) {
            SuperAgentKgCanonicalEntityMember first = entry.getValue().get(0);
            SuperAgentKgCanonicalEntityGroup groupRow = groupById.get(first.getGroupId());
            GraphRagCrossDocumentIndex.CanonicalEntityGroup group = toCanonicalGroup(entry.getKey(), groupRow, entry.getValue());
            for (SuperAgentKgCanonicalEntityMember member : entry.getValue()) {
                canonicalByEntityId.put(member.getEntityId(), group);
            }
        }

        Set<Long> relationIds = relationIdsByDocuments(documentIds, taskIds);
        LinkedHashMap<Long, GraphRagCrossDocumentIndex.RelationGroup> relationByRelationId = new LinkedHashMap<>();
        if (CollUtil.isNotEmpty(relationIds)) {
            List<SuperAgentKgRelationGroupMember> relationMembers = relationGroupMemberMapper.selectList(new LambdaQueryWrapper<SuperAgentKgRelationGroupMember>()
                .eq(SuperAgentKgRelationGroupMember::getScopeKey, scopeKey)
                .in(SuperAgentKgRelationGroupMember::getRelationId, relationIds)
                .eq(SuperAgentKgRelationGroupMember::getStatus, BusinessStatus.YES.getCode()));
            if (CollUtil.isNotEmpty(taskIds)) {
                Set<Long> taskIdSet = new LinkedHashSet<>(taskIds);
                relationMembers = relationMembers.stream()
                    .filter(member -> taskIdSet.contains(member.getTaskId()))
                    .toList();
            }
            Set<Long> relationGroupIds = relationMembers.stream()
                .map(SuperAgentKgRelationGroupMember::getGroupId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<Long, SuperAgentKgRelationGroup> relationGroupById = relationGroupIds.isEmpty()
                ? Map.of()
                : relationGroupMapper.selectList(new LambdaQueryWrapper<SuperAgentKgRelationGroup>()
                    .eq(SuperAgentKgRelationGroup::getScopeKey, scopeKey)
                    .in(SuperAgentKgRelationGroup::getId, relationGroupIds)
                    .eq(SuperAgentKgRelationGroup::getStatus, BusinessStatus.YES.getCode()))
                .stream()
                .collect(Collectors.toMap(SuperAgentKgRelationGroup::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
            Map<String, List<SuperAgentKgRelationGroupMember>> relationMembersByGroupKey = relationMembers.stream()
                .collect(Collectors.groupingBy(SuperAgentKgRelationGroupMember::getGroupKey, LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<String, List<SuperAgentKgRelationGroupMember>> entry : relationMembersByGroupKey.entrySet()) {
                SuperAgentKgRelationGroupMember first = entry.getValue().get(0);
                SuperAgentKgRelationGroup groupRow = relationGroupById.get(first.getGroupId());
                GraphRagCrossDocumentIndex.RelationGroup group = toRelationGroup(entry.getKey(), groupRow, entry.getValue());
                for (SuperAgentKgRelationGroupMember member : entry.getValue()) {
                    relationByRelationId.put(member.getRelationId(), group);
                }
            }
        }
        return withLoadedCommunities(scopeKey, canonicalByEntityId, relationByRelationId,
            new LinkedHashSet<>(documentIds));
    }

    private GraphRagCrossDocumentIndexBuildResult persistScope(String scopeKey, ScopeDataset dataset) {
        GraphRagCrossDocumentIndex index = indexSupport.build(dataset.entities(), dataset.relations(), dataset.evidences());
        Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalGroups = index.distinctCanonicalGroups();
        int memberCount = 0;
        for (GraphRagCrossDocumentIndex.CanonicalEntityGroup group : canonicalGroups.values()) {
            Long groupId = uidGenerator.getUid();
            canonicalGroupMapper.insert(toCanonicalGroupRow(scopeKey, groupId, group));
            for (Long entityId : group.entityIds()) {
                SuperAgentKgEntity entity = dataset.entityById().get(entityId);
                if (entity == null) {
                    continue;
                }
                canonicalMemberMapper.insert(toCanonicalMemberRow(scopeKey, groupId, group, entity));
                memberCount++;
            }
        }

        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationGroups = index.distinctRelationGroups();
        Map<String, Long> relationGroupIdByKey = new LinkedHashMap<>();
        int relationMemberCount = 0;
        for (GraphRagCrossDocumentIndex.RelationGroup group : relationGroups.values()) {
            Long groupId = uidGenerator.getUid();
            relationGroupIdByKey.put(group.key(), groupId);
            relationGroupMapper.insert(toRelationGroupRow(scopeKey, groupId, group));
            for (Long relationId : group.relationIds()) {
                SuperAgentKgRelation relation = dataset.relationById().get(relationId);
                if (relation == null) {
                    continue;
                }
                relationGroupMemberMapper.insert(toRelationGroupMemberRow(scopeKey, groupId, group, relation));
                relationMemberCount++;
            }
        }

        int communityMemberCount = 0;
        for (GraphRagCrossDocumentIndex.CrossDocumentCommunity community : index.communityByKey().values()) {
            Long communityId = uidGenerator.getUid();
            communityMapper.insert(toCommunityRow(scopeKey, communityId, community));
            for (String relationGroupKey : community.relationGroupKeys()) {
                Long relationGroupId = relationGroupIdByKey.get(relationGroupKey);
                GraphRagCrossDocumentIndex.RelationGroup relationGroup = relationGroups.get(relationGroupKey);
                if (relationGroupId == null || relationGroup == null) {
                    continue;
                }
                communityMemberMapper.insert(toCommunityMemberRow(scopeKey, communityId, community, relationGroupId, relationGroup));
                communityMemberCount++;
            }
        }

        return GraphRagCrossDocumentIndexBuildResult.builder()
            .scopeKey(scopeKey)
            .canonicalGroupCount(canonicalGroups.size())
            .canonicalMemberCount(memberCount)
            .relationGroupCount(relationGroups.size())
            .relationGroupMemberCount(relationMemberCount)
            .communityCount(index.communityByKey().size())
            .communityMemberCount(communityMemberCount)
            .build();
    }

    private LinkedHashMap<String, ScopeDataset> buildScopes(List<SuperAgentKgEntity> entities,
                                                            List<SuperAgentKgRelation> relations,
                                                            List<SuperAgentKgEvidence> evidences,
                                                            Map<Long, SuperAgentDocument> documentMap) {
        LinkedHashMap<String, ScopeDataset> scopes = new LinkedHashMap<>();
        scopes.put(GLOBAL_SCOPE_KEY, ScopeDataset.of(entities, relations, evidences));

        Map<Long, String> scopeByDocumentId = new LinkedHashMap<>();
        for (SuperAgentDocument document : documentMap.values()) {
            if (document.getKnowledgeBaseId() != null) {
                scopeByDocumentId.put(document.getId(), RaptorScopeSupport.knowledgeBaseScopeKey(document.getKnowledgeBaseId()));
            }
        }
        appendGroupedScopes(scopes, entities, relations, evidences, scopeByDocumentId);

        Map<Long, List<String>> relationScopeKeysByDocumentId = scopeByDocumentId(documentMap.keySet());
        LinkedHashSet<String> relationScopeKeys = relationScopeKeysByDocumentId.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String scopeKey : relationScopeKeys) {
            Map<Long, String> scopedDocumentMap = new LinkedHashMap<>();
            relationScopeKeysByDocumentId.forEach((documentId, scopeKeys) -> {
                if (scopeKeys.contains(scopeKey)) {
                    scopedDocumentMap.put(documentId, scopeKey);
                }
            });
            appendGroupedScopes(scopes, entities, relations, evidences, scopedDocumentMap);
        }
        return scopes;
    }

    private void appendGroupedScopes(LinkedHashMap<String, ScopeDataset> scopes,
                                     List<SuperAgentKgEntity> entities,
                                     List<SuperAgentKgRelation> relations,
                                     List<SuperAgentKgEvidence> evidences,
                                     Map<Long, String> scopeByDocumentId) {
        if (scopeByDocumentId.isEmpty()) {
            return;
        }
        Map<String, List<SuperAgentKgEntity>> entitiesByScope = entities.stream()
            .filter(entity -> scopeByDocumentId.containsKey(entity.getDocumentId()))
            .collect(Collectors.groupingBy(entity -> scopeByDocumentId.get(entity.getDocumentId()), LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<SuperAgentKgEntity>> entry : entitiesByScope.entrySet()) {
            Set<Long> documentIds = entry.getValue().stream()
                .map(SuperAgentKgEntity::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            List<SuperAgentKgRelation> scopedRelations = relations.stream()
                .filter(relation -> documentIds.contains(relation.getDocumentId()))
                .toList();
            List<SuperAgentKgEvidence> scopedEvidences = evidences.stream()
                .filter(evidence -> documentIds.contains(evidence.getDocumentId()))
                .toList();
            scopes.put(entry.getKey(), ScopeDataset.of(entry.getValue(), scopedRelations, scopedEvidences));
        }
    }

    private String resolveLoadScopeKey(List<Long> documentIds) {
        Map<Long, SuperAgentDocument> documents = listDocuments(new LinkedHashSet<>(documentIds));
        LinkedHashSet<Long> knowledgeBaseIds = documents.values().stream()
            .map(SuperAgentDocument::getKnowledgeBaseId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, List<String>> scopeKeysByDocumentId = scopeByDocumentId(new LinkedHashSet<>(documentIds));
        LinkedHashSet<String> sharedScopeKeys = scopeKeysByDocumentId.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (knowledgeBaseIds.size() == 1 && sharedScopeKeys.size() == 1) {
            return sharedScopeKeys.iterator().next();
        }
        if (knowledgeBaseIds.size() == 1) {
            return RaptorScopeSupport.knowledgeBaseScopeKey(knowledgeBaseIds.iterator().next());
        }
        return GLOBAL_SCOPE_KEY;
    }

    private Map<Long, List<String>> scopeByDocumentId(Collection<Long> documentIds) {
        if (CollUtil.isEmpty(documentIds)) {
            return Map.of();
        }
        List<SuperAgentTopicDocumentRelation> relations = topicDocumentRelationMapper.selectList(new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .in(SuperAgentTopicDocumentRelation::getDocumentId, documentIds)
            .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode()));
        if (relations.isEmpty()) {
            return Map.of();
        }
        Map<Long, SuperAgentKnowledgeTopicNode> topicById = topicNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
                .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .filter(topic -> topic.getId() != null)
            .collect(Collectors.toMap(
                SuperAgentKnowledgeTopicNode::getId,
                topic -> topic,
                (left, right) -> left,
                LinkedHashMap::new));
        Map<Long, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (SuperAgentTopicDocumentRelation relation : relations) {
            SuperAgentKnowledgeTopicNode topic = topicById.get(relation.getTopicId());
            if (topic == null || topic.getKnowledgeBaseId() == null || topic.getScopeId() == null) {
                continue;
            }
            grouped.computeIfAbsent(relation.getDocumentId(), ignored -> new LinkedHashSet<>())
                .add(RaptorScopeSupport.knowledgeScopeKey(topic.getKnowledgeBaseId(), topic.getScopeId()));
        }
        return grouped.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue()), (left, right) -> left, LinkedHashMap::new));
    }

    private List<SuperAgentKgEntity> listEntities(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgEntity> wrapper = new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(documentIds)) {
            wrapper.in(SuperAgentKgEntity::getDocumentId, documentIds);
        }
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgEntity::getTaskId, taskIds);
        }
        return entityMapper.selectList(wrapper);
    }

    private List<SuperAgentKgRelation> listRelations(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgRelation> wrapper = new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(documentIds)) {
            wrapper.in(SuperAgentKgRelation::getDocumentId, documentIds);
        }
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgRelation::getTaskId, taskIds);
        }
        return relationMapper.selectList(wrapper);
    }

    private List<SuperAgentKgEvidence> listEvidences(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgEvidence> wrapper = new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(documentIds)) {
            wrapper.in(SuperAgentKgEvidence::getDocumentId, documentIds);
        }
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgEvidence::getTaskId, taskIds);
        }
        return evidenceMapper.selectList(wrapper);
    }

    private Set<Long> relationIdsByDocuments(List<Long> documentIds, List<Long> taskIds) {
        return listRelations(documentIds, taskIds).stream()
            .map(SuperAgentKgRelation::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<Long, SuperAgentDocument> listDocuments(Set<Long> documentIds) {
        if (CollUtil.isEmpty(documentIds)) {
            return Map.of();
        }
        return documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
                .in(SuperAgentDocument::getId, documentIds)
                .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.toMap(SuperAgentDocument::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
    }

    private SuperAgentKgCanonicalEntityGroup toCanonicalGroupRow(String scopeKey,
                                                                 Long groupId,
                                                                 GraphRagCrossDocumentIndex.CanonicalEntityGroup group) {
        SuperAgentKgCanonicalEntityGroup row = new SuperAgentKgCanonicalEntityGroup();
        row.setId(groupId);
        row.setScopeKey(scopeKey);
        row.setGroupKey(limit(group.key(), 255));
        row.setCanonicalName(limit(group.name(), 500));
        row.setEntityType(limit(group.entityType(), 64));
        row.setEntityCount(size(group.entityIds()));
        row.setDocumentCount(size(group.documentIds()));
        row.setTaskCount(size(group.taskIds()));
        row.setRankScore(decimal(group.rankScore()));
        Map<String, Object> metadata = baseDerivedMetadata(group.qualityProfile(), group.rankProfile());
        metadata.put("variants", group.variants());
        metadata.put("entityIds", group.entityIds());
        metadata.put("documentIds", group.documentIds());
        metadata.put("taskIds", group.taskIds());
        row.setMetadataJson(writeJson(metadata));
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private SuperAgentKgCanonicalEntityMember toCanonicalMemberRow(String scopeKey,
                                                                   Long groupId,
                                                                   GraphRagCrossDocumentIndex.CanonicalEntityGroup group,
                                                                   SuperAgentKgEntity entity) {
        SuperAgentKgCanonicalEntityMember row = new SuperAgentKgCanonicalEntityMember();
        row.setId(uidGenerator.getUid());
        row.setScopeKey(scopeKey);
        row.setGroupId(groupId);
        row.setGroupKey(limit(group.key(), 255));
        row.setEntityId(entity.getId());
        row.setDocumentId(entity.getDocumentId());
        row.setTaskId(entity.getTaskId());
        row.setEntityName(limit(entity.getName(), 500));
        row.setNormalizedName(limit(entity.getNormalizedName(), 500));
        row.setEntityType(limit(entity.getEntityType(), 64));
        Map<String, Object> metadata = baseDerivedMetadata(group.qualityProfile(), group.rankProfile());
        metadata.put("canonicalName", group.name());
        metadata.put("rankScore", group.rankScore());
        row.setMetadataJson(writeJson(metadata));
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private SuperAgentKgRelationGroup toRelationGroupRow(String scopeKey,
                                                         Long groupId,
                                                         GraphRagCrossDocumentIndex.RelationGroup group) {
        SuperAgentKgRelationGroup row = new SuperAgentKgRelationGroup();
        row.setId(groupId);
        row.setScopeKey(scopeKey);
        row.setGroupKey(relationGroupStorageKey(group.key()));
        row.setSourceGroupKey(limit(group.sourceGroupKey(), 255));
        row.setTargetGroupKey(limit(group.targetGroupKey(), 255));
        row.setRelationType(limit(group.relationType(), 64));
        row.setRelationCount(group.relationCount());
        row.setEvidenceCount(group.evidenceCount());
        row.setDocumentCount(group.documentCount());
        row.setRankScore(decimal(group.rankScore()));
        Map<String, Object> metadata = baseDerivedMetadata(group.qualityProfile(), group.rankProfile());
        metadata.put("naturalGroupKey", group.key());
        metadata.put("relationIds", group.relationIds());
        metadata.put("evidenceIds", group.evidenceIds());
        metadata.put("documentIds", group.documentIds());
        row.setMetadataJson(writeJson(metadata));
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private SuperAgentKgRelationGroupMember toRelationGroupMemberRow(String scopeKey,
                                                                     Long groupId,
                                                                     GraphRagCrossDocumentIndex.RelationGroup group,
                                                                     SuperAgentKgRelation relation) {
        SuperAgentKgRelationGroupMember row = new SuperAgentKgRelationGroupMember();
        row.setId(uidGenerator.getUid());
        row.setScopeKey(scopeKey);
        row.setGroupId(groupId);
        row.setGroupKey(relationGroupStorageKey(group.key()));
        row.setRelationId(relation.getId());
        row.setDocumentId(relation.getDocumentId());
        row.setTaskId(relation.getTaskId());
        int evidenceCount = group.evidenceCountByRelationId().getOrDefault(relation.getId(), 0);
        row.setEvidenceCount(evidenceCount);
        Map<String, Object> metadata = baseDerivedMetadata(group.qualityProfile(), group.rankProfile());
        metadata.put("naturalGroupKey", group.key());
        metadata.put("relationType", StrUtil.blankToDefault(relation.getRelationType(), "ASSOCIATED_WITH"));
        metadata.put("evidenceCount", evidenceCount);
        metadata.put("rankScore", group.rankScore());
        row.setMetadataJson(writeJson(metadata));
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private SuperAgentKgCrossDocumentCommunity toCommunityRow(String scopeKey,
                                                              Long communityId,
                                                              GraphRagCrossDocumentIndex.CrossDocumentCommunity community) {
        SuperAgentKgCrossDocumentCommunity row = new SuperAgentKgCrossDocumentCommunity();
        row.setId(communityId);
        row.setScopeKey(scopeKey);
        row.setCommunityKey(limit(community.key(), 255));
        row.setTitle(limit(community.title(), 500));
        row.setSummary(community.summary());
        row.setCanonicalGroupKeysJson(writeJson(community.canonicalGroupKeys()));
        row.setRelationGroupKeysJson(writeJson(community.relationGroupKeys().stream()
            .map(this::relationGroupStorageKey)
            .toList()));
        row.setEntityCount(community.entityCount());
        row.setRelationGroupCount(community.relationGroupCount());
        row.setEvidenceCount(community.evidenceCount());
        row.setDocumentCount(community.documentCount());
        row.setRankScore(decimal(community.rankScore()));
        Map<String, Object> metadata = baseDerivedMetadata(community.qualityProfile(), community.rankProfile());
        metadata.put("communityKey", community.key());
        metadata.put("canonicalGroupKeys", community.canonicalGroupKeys());
        metadata.put("relationGroupKeys", community.relationGroupKeys());
        metadata.put("evidenceIds", community.evidenceIds());
        metadata.put("documentIds", community.documentIds());
        metadata.put("reportProfile", reportProfileMap(community.reportProfile()));
        metadata.put("sourceType", "java.cross_document_community.v1");
        row.setMetadataJson(writeJson(metadata));
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private SuperAgentKgCrossDocumentCommunityMember toCommunityMemberRow(
        String scopeKey,
        Long communityId,
        GraphRagCrossDocumentIndex.CrossDocumentCommunity community,
        Long relationGroupId,
        GraphRagCrossDocumentIndex.RelationGroup relationGroup
    ) {
        SuperAgentKgCrossDocumentCommunityMember row = new SuperAgentKgCrossDocumentCommunityMember();
        row.setId(uidGenerator.getUid());
        row.setScopeKey(scopeKey);
        row.setCommunityId(communityId);
        row.setCommunityKey(limit(community.key(), 255));
        row.setRelationGroupId(relationGroupId);
        row.setRelationGroupKey(relationGroupStorageKey(relationGroup.key()));
        row.setSourceGroupKey(limit(relationGroup.sourceGroupKey(), 255));
        row.setTargetGroupKey(limit(relationGroup.targetGroupKey(), 255));
        row.setRelationType(limit(relationGroup.relationType(), 64));
        row.setRelationCount(relationGroup.relationCount());
        row.setEvidenceCount(relationGroup.evidenceCount());
        row.setDocumentCount(relationGroup.documentCount());
        Map<String, Object> metadata = baseDerivedMetadata(relationGroup.qualityProfile(), relationGroup.rankProfile());
        metadata.put("communityKey", community.key());
        metadata.put("naturalRelationGroupKey", relationGroup.key());
        metadata.put("rankScore", community.rankScore());
        row.setMetadataJson(writeJson(metadata));
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private GraphRagCrossDocumentIndex.CanonicalEntityGroup toCanonicalGroup(String groupKey,
                                                                            SuperAgentKgCanonicalEntityGroup groupRow,
                                                                            List<SuperAgentKgCanonicalEntityMember> members) {
        Set<Long> entityIds = members.stream()
            .map(SuperAgentKgCanonicalEntityMember::getEntityId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> documentIds = members.stream()
            .map(SuperAgentKgCanonicalEntityMember::getDocumentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> taskIds = members.stream()
            .map(SuperAgentKgCanonicalEntityMember::getTaskId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        SuperAgentKgCanonicalEntityMember first = members.get(0);
        Map<String, Object> metadata = readMetadata(groupRow == null ? null : groupRow.getMetadataJson());
        GraphRagCrossDocumentIndex.QualityProfile qualityProfile = qualityProfile(metadata);
        GraphRagCrossDocumentIndex.RankProfile rankProfile = rankProfile(metadata);
        return new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
            groupKey,
            groupRow == null ? first.getEntityName() : groupRow.getCanonicalName(),
            groupRow == null ? first.getEntityType() : groupRow.getEntityType(),
            entityIds,
            documentIds,
            taskIds,
            List.of(),
            groupRow == null || groupRow.getRankScore() == null ? 0D : groupRow.getRankScore().doubleValue(),
            qualityProfile,
            rankProfile
        );
    }

    private GraphRagCrossDocumentIndex.RelationGroup toRelationGroup(String groupKey,
                                                                     SuperAgentKgRelationGroup groupRow,
                                                                     List<SuperAgentKgRelationGroupMember> members) {
        Set<Long> relationIds = members.stream()
            .map(SuperAgentKgRelationGroupMember::getRelationId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> documentIds = members.stream()
            .map(SuperAgentKgRelationGroupMember::getDocumentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        String metadataJson = groupRow == null ? null : groupRow.getMetadataJson();
        Map<String, Object> metadata = readMetadata(metadataJson);
        String naturalGroupKey = StrUtil.blankToDefault(stringValue(metadata.get("naturalGroupKey")), groupKey);
        Set<Long> evidenceIds = readLongSet(metadata, "evidenceIds");
        GraphRagCrossDocumentIndex.QualityProfile qualityProfile = qualityProfile(metadata);
        GraphRagCrossDocumentIndex.RankProfile rankProfile = rankProfile(metadata);
        Map<Long, Integer> evidenceCountByRelationId = members.stream()
            .filter(member -> member.getRelationId() != null)
            .collect(Collectors.toMap(
                SuperAgentKgRelationGroupMember::getRelationId,
                member -> member.getEvidenceCount() == null ? 0 : member.getEvidenceCount(),
                Integer::sum,
                LinkedHashMap::new
            ));
        return new GraphRagCrossDocumentIndex.RelationGroup(
            naturalGroupKey,
            groupRow == null ? "" : groupRow.getSourceGroupKey(),
            groupRow == null ? "" : groupRow.getTargetGroupKey(),
            groupRow == null ? "" : groupRow.getRelationType(),
            relationIds,
            evidenceIds,
            documentIds,
            evidenceCountByRelationId,
            groupRow == null || groupRow.getRankScore() == null ? 0D : groupRow.getRankScore().doubleValue(),
            qualityProfile,
            rankProfile
        );
    }

    private GraphRagCrossDocumentIndex withLoadedCommunities(
        String scopeKey,
        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByEntityId,
        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> relationByRelationId,
        Set<Long> allowedDocumentIds
    ) {
        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationByStorageKey = new LinkedHashMap<>();
        for (GraphRagCrossDocumentIndex.RelationGroup group : relationByRelationId.values()) {
            if (group != null) {
                relationByStorageKey.putIfAbsent(relationGroupStorageKey(group.key()), group);
            }
        }
        if (relationByStorageKey.isEmpty()) {
            return new GraphRagCrossDocumentIndex(canonicalByEntityId, relationByRelationId);
        }

        List<SuperAgentKgCrossDocumentCommunityMember> members = communityMemberMapper.selectList(
            new LambdaQueryWrapper<SuperAgentKgCrossDocumentCommunityMember>()
                .eq(SuperAgentKgCrossDocumentCommunityMember::getScopeKey, scopeKey)
                .in(SuperAgentKgCrossDocumentCommunityMember::getRelationGroupKey, relationByStorageKey.keySet())
                .eq(SuperAgentKgCrossDocumentCommunityMember::getStatus, BusinessStatus.YES.getCode())
        );
        if (members.isEmpty()) {
            return new GraphRagCrossDocumentIndex(canonicalByEntityId, relationByRelationId);
        }
        Set<Long> communityIds = members.stream()
            .map(SuperAgentKgCrossDocumentCommunityMember::getCommunityId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SuperAgentKgCrossDocumentCommunity> communityRowById = communityIds.isEmpty()
            ? Map.of()
            : communityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgCrossDocumentCommunity>()
                .eq(SuperAgentKgCrossDocumentCommunity::getScopeKey, scopeKey)
                .in(SuperAgentKgCrossDocumentCommunity::getId, communityIds)
                .eq(SuperAgentKgCrossDocumentCommunity::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.toMap(SuperAgentKgCrossDocumentCommunity::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<Long, List<SuperAgentKgCrossDocumentCommunityMember>> membersByCommunityId = members.stream()
            .filter(member -> member.getCommunityId() != null)
            .collect(Collectors.groupingBy(SuperAgentKgCrossDocumentCommunityMember::getCommunityId, LinkedHashMap::new, Collectors.toList()));

        LinkedHashMap<String, GraphRagCrossDocumentIndex.CrossDocumentCommunity> communityByKey = new LinkedHashMap<>();
        LinkedHashMap<String, GraphRagCrossDocumentIndex.CrossDocumentCommunity> communityByRelationGroupKey = new LinkedHashMap<>();
        for (Map.Entry<Long, List<SuperAgentKgCrossDocumentCommunityMember>> entry : membersByCommunityId.entrySet()) {
            SuperAgentKgCrossDocumentCommunity row = communityRowById.get(entry.getKey());
            if (row == null) {
                continue;
            }
            GraphRagCrossDocumentIndex.CrossDocumentCommunity community = toCommunity(row, entry.getValue(), relationByStorageKey);
            if (!communityScopeFullyVisible(community, allowedDocumentIds)) {
                // 跨文档社区的标题、摘要与证据都是对**全部来源文档**聚合生成的整段内容，
                // 术后无法按来源裁剪。可见范围不覆盖全部来源时整条社区不进入候选：任何
                // "有交集就返回"的语义都会把范围外文档的内容带进证据。
                continue;
            }
            communityByKey.put(community.key(), community);
            for (String relationGroupKey : community.relationGroupKeys()) {
                communityByRelationGroupKey.put(relationGroupKey, community);
            }
        }
        return new GraphRagCrossDocumentIndex(canonicalByEntityId, relationByRelationId, communityByKey, communityByRelationGroupKey);
    }

    /**
     * 跨文档社区是否完全落在可见范围内（文档维度 ALL 语义）。
     *
     * <p>来源文档集合缺失（元数据为空）时按不可见处理：没有来源记录就无法证明它只由可见文档聚合而来，
     * 这种情况必须收窄而不是放行。</p>
     */
    boolean communityScopeFullyVisible(GraphRagCrossDocumentIndex.CrossDocumentCommunity community,
                                      Set<Long> allowedDocumentIds) {
        Set<Long> sourceDocumentIds = community.documentIds();
        if (sourceDocumentIds == null || sourceDocumentIds.isEmpty()) {
            return false;
        }
        return allowedDocumentIds != null && allowedDocumentIds.containsAll(sourceDocumentIds);
    }

    private GraphRagCrossDocumentIndex.CrossDocumentCommunity toCommunity(
        SuperAgentKgCrossDocumentCommunity row,
        List<SuperAgentKgCrossDocumentCommunityMember> members,
        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationByStorageKey
    ) {
        Map<String, Object> metadata = readMetadata(row.getMetadataJson());
        String communityKey = StrUtil.blankToDefault(stringValue(metadata.get("communityKey")), row.getCommunityKey());
        Set<String> relationGroupKeys = new LinkedHashSet<>();
        for (SuperAgentKgCrossDocumentCommunityMember member : members) {
            GraphRagCrossDocumentIndex.RelationGroup relationGroup = relationByStorageKey.get(member.getRelationGroupKey());
            if (relationGroup != null) {
                relationGroupKeys.add(relationGroup.key());
            }
        }
        if (relationGroupKeys.isEmpty()) {
            relationGroupKeys.addAll(readStringSet(metadata, "relationGroupKeys"));
        }
        return new GraphRagCrossDocumentIndex.CrossDocumentCommunity(
            row.getId(),
            communityKey,
            row.getTitle(),
            row.getSummary(),
            readStringSet(metadata, "canonicalGroupKeys"),
            relationGroupKeys,
            readLongSet(metadata, "evidenceIds"),
            readLongSet(metadata, "documentIds"),
            readReportProfile(metadata),
            row.getRankScore() == null ? 0D : row.getRankScore().doubleValue(),
            qualityProfile(metadata),
            rankProfile(metadata)
        );
    }

    private GraphRagCrossDocumentIndex.QualityProfile qualityProfile(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return GraphRagCrossDocumentIndex.QualityProfile.empty();
        }
        return new GraphRagCrossDocumentIndex.QualityProfile(
            numberValue(metadata.get("qualityScore"), 0D),
            readStringList(metadata.get("qualityReasons")),
            readStringList(metadata.get("noiseReasons"))
        );
    }

    private Map<String, Object> baseDerivedMetadata(GraphRagCrossDocumentIndex.QualityProfile qualityProfile,
                                                    GraphRagCrossDocumentIndex.RankProfile rankProfile) {
        GraphRagCrossDocumentIndex.QualityProfile safeQuality = qualityProfile == null
            ? GraphRagCrossDocumentIndex.QualityProfile.empty()
            : qualityProfile;
        GraphRagCrossDocumentIndex.RankProfile safeRank = rankProfile == null
            ? GraphRagCrossDocumentIndex.RankProfile.empty()
            : rankProfile;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceType", DERIVED_INDEX_SOURCE_TYPE);
        metadata.put("rankAlgorithm", "java.cross_document_pagerank.v1");
        metadata.put("qualityScore", safeQuality.score());
        metadata.put("qualityReasons", safeQuality.qualityReasons());
        metadata.put("noiseReasons", safeQuality.noiseReasons());
        metadata.put("pagerank", safeRank.pagerank());
        metadata.put("rankBoost", safeRank.rankBoost());
        metadata.put("rankPosition", safeRank.rankPosition());
        metadata.put("degree", safeRank.degree());
        metadata.put("inDegree", safeRank.inDegree());
        metadata.put("outDegree", safeRank.outDegree());
        metadata.put("weightedDegree", safeRank.weightedDegree());
        return metadata;
    }

    private Map<String, Object> reportProfileMap(GraphRagCrossDocumentIndex.ReportProfile reportProfile) {
        GraphRagCrossDocumentIndex.ReportProfile safeProfile = reportProfile == null
            ? GraphRagCrossDocumentIndex.ReportProfile.empty()
            : reportProfile;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("strategy", safeProfile.strategy());
        metadata.put("coreEntityNames", safeProfile.coreEntityNames());
        metadata.put("keyRelationTypes", safeProfile.keyRelationTypes());
        metadata.put("evidenceBoundaries", safeProfile.evidenceBoundaries());
        metadata.put("cannotInfer", safeProfile.cannotInfer());
        metadata.put("qualityScore", safeProfile.qualityScore());
        metadata.put("qualityReasons", safeProfile.qualityReasons());
        return metadata;
    }

    private GraphRagCrossDocumentIndex.ReportProfile readReportProfile(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return GraphRagCrossDocumentIndex.ReportProfile.empty();
        }
        Object value = metadata.get("reportProfile");
        if (!(value instanceof Map<?, ?> map)) {
            return GraphRagCrossDocumentIndex.ReportProfile.empty();
        }
        return new GraphRagCrossDocumentIndex.ReportProfile(
            stringValue(map.get("strategy")),
            readStringList(map.get("coreEntityNames")),
            readStringList(map.get("keyRelationTypes")),
            readStringList(map.get("evidenceBoundaries")),
            readStringList(map.get("cannotInfer")),
            numberValue(map.get("qualityScore"), 0D),
            readStringList(map.get("qualityReasons"))
        );
    }

    private GraphRagCrossDocumentIndex.RankProfile rankProfile(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return GraphRagCrossDocumentIndex.RankProfile.empty();
        }
        return new GraphRagCrossDocumentIndex.RankProfile(
            numberValue(metadata.get("pagerank"), 0D),
            numberValue(metadata.get("rankBoost"), 0D),
            integerValue(metadata.get("rankPosition")),
            integerValue(metadata.get("degree")),
            integerValue(metadata.get("inDegree")),
            integerValue(metadata.get("outDegree")),
            doubleValue(metadata.get("weightedDegree"), 0D)
        );
    }

    private Set<Long> readLongSet(String metadataJson, String key) {
        return readLongSet(readMetadata(metadataJson), key);
    }

    private Set<Long> readLongSet(Map<String, Object> map, String key) {
        if (map.isEmpty()) {
            return Set.of();
        }
        Object value = map.get(key);
        if (!(value instanceof Collection<?> collection)) {
            return Set.of();
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Object item : collection) {
            if (item instanceof Number number) {
                result.add(number.longValue());
            }
            else if (item != null) {
                try {
                    result.add(Long.parseLong(String.valueOf(item)));
                }
                catch (NumberFormatException ignored) {
                    // Ignore malformed metadata values in the derived read model.
                }
            }
        }
        return result;
    }

    private Set<String> readStringSet(Map<String, Object> map, String key) {
        if (map == null || map.isEmpty()) {
            return Set.of();
        }
        Object value = map.get(key);
        if (!(value instanceof Collection<?> collection)) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : collection) {
            if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private List<String> readStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            List<String> result = new ArrayList<>();
            for (Object item : collection) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (value instanceof String text) {
            if (StrUtil.isBlank(text)) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (String item : text.split("[\\n\\r,，;；、|]+")) {
                if (StrUtil.isNotBlank(item)) {
                    result.add(item.trim());
                }
            }
            return result;
        }
        return List.of(String.valueOf(value));
    }

    private double numberValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return Math.max(0D, Math.min(1D, number.doubleValue()));
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Math.max(0D, Math.min(1D, Double.parseDouble(String.valueOf(value))));
        }
        catch (Exception exception) {
            return defaultValue;
        }
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return Math.max(0D, number.doubleValue());
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Math.max(0D, Double.parseDouble(String.valueOf(value)));
        }
        catch (Exception exception) {
            return defaultValue;
        }
    }

    private int integerValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        }
        catch (Exception exception) {
            return 0;
        }
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (StrUtil.isBlank(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        }
        catch (Exception exception) {
            return Map.of();
        }
    }

    private void deleteAllDerivedIndexes() {
        communityMemberMapper.delete(new LambdaQueryWrapper<SuperAgentKgCrossDocumentCommunityMember>()
            .isNotNull(SuperAgentKgCrossDocumentCommunityMember::getId));
        communityMapper.delete(new LambdaQueryWrapper<SuperAgentKgCrossDocumentCommunity>()
            .isNotNull(SuperAgentKgCrossDocumentCommunity::getId));
        relationGroupMemberMapper.delete(new LambdaQueryWrapper<SuperAgentKgRelationGroupMember>()
            .isNotNull(SuperAgentKgRelationGroupMember::getId));
        relationGroupMapper.delete(new LambdaQueryWrapper<SuperAgentKgRelationGroup>()
            .isNotNull(SuperAgentKgRelationGroup::getId));
        canonicalMemberMapper.delete(new LambdaQueryWrapper<SuperAgentKgCanonicalEntityMember>()
            .isNotNull(SuperAgentKgCanonicalEntityMember::getId));
        canonicalGroupMapper.delete(new LambdaQueryWrapper<SuperAgentKgCanonicalEntityGroup>()
            .isNotNull(SuperAgentKgCanonicalEntityGroup::getId));
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private int size(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("GraphRAG 跨文档派生索引 JSON 序列化失败", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String relationGroupStorageKey(String naturalGroupKey) {
        return "sha256:" + sha256Hex(StrUtil.blankToDefault(naturalGroupKey, ""));
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用，无法生成 GraphRAG 跨文档关系组持久化 key", exception);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record ScopeDataset(List<SuperAgentKgEntity> entities,
                                List<SuperAgentKgRelation> relations,
                                List<SuperAgentKgEvidence> evidences,
                                Map<Long, SuperAgentKgEntity> entityById,
                                Map<Long, SuperAgentKgRelation> relationById) {

        private static ScopeDataset of(List<SuperAgentKgEntity> entities,
                                       List<SuperAgentKgRelation> relations,
                                       List<SuperAgentKgEvidence> evidences) {
            return new ScopeDataset(
                entities == null ? List.of() : entities,
                relations == null ? List.of() : relations,
                evidences == null ? List.of() : evidences,
                (entities == null ? List.<SuperAgentKgEntity>of() : entities).stream()
                    .filter(entity -> entity != null && entity.getId() != null)
                    .collect(Collectors.toMap(SuperAgentKgEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new)),
                (relations == null ? List.<SuperAgentKgRelation>of() : relations).stream()
                    .filter(relation -> relation != null && relation.getId() != null)
                    .collect(Collectors.toMap(SuperAgentKgRelation::getId, item -> item, (left, right) -> left, LinkedHashMap::new))
            );
        }
    }
}
