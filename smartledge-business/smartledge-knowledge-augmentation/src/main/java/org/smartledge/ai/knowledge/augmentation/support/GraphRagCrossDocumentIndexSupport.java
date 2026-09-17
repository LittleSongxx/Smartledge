package org.smartledge.ai.knowledge.augmentation.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEntity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEvidence;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GraphRagCrossDocumentIndexSupport {

    private static final Set<String> TECHNICAL_ENTITY_LABELS = Set.of(
        "title", "section", "content", "keywords", "questions", "chunktype", "chunk_type",
        "text", "metadata", "page", "bbox", "ct"
    );
    private static final Set<String> GENERATED_EVIDENCE_MARKERS = Set.of(
        "[QUESTIONS]", "[KEYWORDS]"
    );
    private static final Set<String> STRONG_ENTITY_SOURCES = Set.of(
        "schema.explicitalias", "llm.controlled.extract.v1", "ner", "ner.pattern"
    );
    private static final Set<String> STRONG_RELATION_SOURCES = Set.of(
        "llm.controlled.extract.v1"
    );
    private static final Set<String> WEAK_ENTITY_SOURCES = Set.of(
        "schema.title", "schema.sectionpath", "ner.pattern.mixedphrase"
    );

    private final ObjectMapper objectMapper;

    public GraphRagCrossDocumentIndexSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GraphRagCrossDocumentIndex build(List<SuperAgentKgEntity> entities,
                                            Collection<SuperAgentKgRelation> relations,
                                            List<SuperAgentKgEvidence> evidences) {
        Map<Long, SuperAgentKgEntity> entityMap = safeList(entities).stream()
            .filter(entity -> entity != null && entity.getId() != null)
            .collect(Collectors.toMap(SuperAgentKgEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalGroups = buildCanonicalEntityGroups(entityMap.values());
        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> relationGroups = buildRelationGroups(relations, evidences, entityMap, canonicalGroups);
        return withGlobalRank(canonicalGroups, relationGroups);
    }

    public boolean isGraphSearchEntityUsable(SuperAgentKgEntity entity) {
        if (entity == null) {
            return false;
        }
        String name = normalizeCanonicalVariant(entity.getName());
        String normalizedName = normalizeCanonicalVariant(StrUtil.blankToDefault(entity.getNormalizedName(), entity.getName()));
        return !isTechnicalEntityName(name)
            && !isTechnicalEntityName(normalizedName);
    }

    public boolean isGraphEvidenceUsable(SuperAgentKgEvidence evidence) {
        if (evidence == null || StrUtil.isBlank(evidence.getQuoteText())) {
            return false;
        }
        String upperQuote = evidence.getQuoteText().toUpperCase(Locale.ROOT);
        for (String marker : GENERATED_EVIDENCE_MARKERS) {
            if (upperQuote.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    public String relationGroupKey(SuperAgentKgRelation relation,
                                   SuperAgentKgEntity source,
                                   SuperAgentKgEntity target,
                                   Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalGroups) {
        String sourceKey = canonicalEntityGroupKey(source, canonicalGroups);
        String targetKey = canonicalEntityGroupKey(target, canonicalGroups);
        String relationType = StrUtil.blankToDefault(relation == null ? null : relation.getRelationType(), "ASSOCIATED_WITH")
            .trim()
            .toUpperCase(Locale.ROOT);
        return sourceKey + "->" + relationType + "->" + targetKey;
    }

    public String canonicalEntityGroupKey(SuperAgentKgEntity entity,
                                          Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalGroups) {
        GraphRagCrossDocumentIndex.CanonicalEntityGroup group = canonicalGroups == null || entity == null
            ? null
            : canonicalGroups.get(entity.getId());
        if (group != null && StrUtil.isNotBlank(group.key())) {
            return group.key();
        }
        String entityType = canonicalEntityType(entity);
        String name = entity == null ? "" : StrUtil.blankToDefault(entity.getNormalizedName(), entity.getName());
        String normalizedName = normalizeCanonicalVariant(name);
        String key = entityType + ":" + normalizedName;
        if (!isCanonicalVariantUsable(normalizedName)) {
            Long entityId = entity == null ? null : entity.getId();
            key += "#" + (entityId == null ? "unknown" : entityId);
        }
        return key;
    }

    public String canonicalEntityType(SuperAgentKgEntity entity) {
        return StrUtil.blankToDefault(entity == null ? null : entity.getEntityType(), "CONCEPT")
            .trim()
            .toUpperCase(Locale.ROOT);
    }

    public List<String> entityAliases(SuperAgentKgEntity entity) {
        Map<String, Object> metadata = readMap(entity == null ? null : entity.getMetadataJson());
        return readStringList(metadata.get("aliases"));
    }

    public Set<String> entityCandidateSources(SuperAgentKgEntity entity) {
        if (entity == null) {
            return Set.of();
        }
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        Map<String, Object> metadata = readMap(entity.getMetadataJson());
        sources.addAll(readStringList(metadata.get("candidateSources")));
        Object sourceMetadata = metadata.get("sourceMetadata");
        if (sourceMetadata instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Map<?, ?> map) {
                    sources.addAll(readStringList(map.get("candidateSources")));
                }
            }
        }
        return sources;
    }

    public double entityRankBoost(SuperAgentKgEntity entity) {
        Map<String, Object> metadata = readMap(entity == null ? null : entity.getMetadataJson());
        return numberValue(metadata.get("rankBoost"), 0D);
    }

    public double relationRankBoost(SuperAgentKgRelation relation,
                                    SuperAgentKgEntity source,
                                    SuperAgentKgEntity target) {
        Map<String, Object> metadata = readMap(relation == null ? null : relation.getMetadataJson());
        double relationRankBoost = numberValue(metadata.get("rankBoost"), -1D);
        if (relationRankBoost >= 0D) {
            return relationRankBoost;
        }
        return Math.max(entityRankBoost(source), entityRankBoost(target));
    }

    public String normalizeCanonicalVariant(String value) {
        return normalize(value).replaceAll("[<>《》/\\\\|?？!！]+", "");
    }

    public boolean isDistinctiveCanonicalVariant(String normalized) {
        if (StrUtil.isBlank(normalized)) {
            return false;
        }
        boolean hasLatinOrDigit = normalized.chars()
            .anyMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'));
        if (hasLatinOrDigit) {
            return normalized.length() >= 2;
        }
        return normalized.codePointCount(0, normalized.length()) >= 3;
    }

    public boolean isCanonicalVariantUsable(String normalized) {
        if (StrUtil.isBlank(normalized) || normalized.length() < 2 || normalized.length() > 80) {
            return false;
        }
        return !isTechnicalEntityName(normalized) && isDistinctiveCanonicalVariant(normalized);
    }

    public double relationGroupBoost(GraphRagCrossDocumentIndex.RelationGroup group) {
        if (group == null) {
            return 0D;
        }
        double boost = 0D;
        if (group.relationCount() > 1) {
            boost += Math.min(0.16D, (group.relationCount() - 1) * 0.06D);
        }
        if (group.evidenceCount() > 1) {
            boost += Math.min(0.12D, (group.evidenceCount() - 1) * 0.04D);
        }
        if (group.documentCount() > 1) {
            boost += Math.min(0.18D, (group.documentCount() - 1) * 0.09D);
        }
        double qualityScore = group.qualityProfile() == null ? 0D : group.qualityProfile().score();
        boost += Math.min(0.1D, qualityScore * 0.1D);
        double rankBoost = group.rankProfile() == null ? 0D : group.rankProfile().rankBoost();
        boost += Math.min(0.08D, rankBoost * 0.08D);
        return Math.min(0.42D, boost);
    }

    public Map<String, Object> readMap(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        }
        catch (Exception exception) {
            return Map.of();
        }
    }

    public List<String> readStringList(Object value) {
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

    public double numberValue(Object value, double defaultValue) {
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

    public String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public String normalize(String text) {
        return StrUtil.blankToDefault(text, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> buildCanonicalEntityGroups(Collection<SuperAgentKgEntity> entities) {
        if (CollUtil.isEmpty(entities)) {
            return Map.of();
        }
        Map<Long, Long> parent = new LinkedHashMap<>();
        Map<Long, SuperAgentKgEntity> entityById = new LinkedHashMap<>();
        for (SuperAgentKgEntity entity : entities) {
            if (entity == null || entity.getId() == null || !isGraphSearchEntityUsable(entity)) {
                continue;
            }
            parent.put(entity.getId(), entity.getId());
            entityById.put(entity.getId(), entity);
        }
        Map<Long, CanonicalEntityVariants> variantsByEntityId = new LinkedHashMap<>();
        Map<String, Long> firstEntityByAnchor = new LinkedHashMap<>();
        Map<String, List<Long>> entityIdsByAnchorName = new LinkedHashMap<>();
        for (SuperAgentKgEntity entity : entityById.values()) {
            CanonicalEntityVariants variants = canonicalEntityVariants(entity);
            variantsByEntityId.put(entity.getId(), variants);
            for (String variantKey : variants.anchorKeys()) {
                Long firstEntityId = firstEntityByAnchor.putIfAbsent(variantKey, entity.getId());
                if (firstEntityId != null) {
                    union(parent, firstEntityId, entity.getId());
                }
            }
            for (String anchorName : variants.anchorNames()) {
                entityIdsByAnchorName.computeIfAbsent(anchorName, ignored -> new ArrayList<>()).add(entity.getId());
            }
        }
        for (SuperAgentKgEntity entity : entityById.values()) {
            CanonicalEntityVariants variants = variantsByEntityId.get(entity.getId());
            for (String aliasKey : variants.aliasKeys()) {
                Long anchorEntityId = firstEntityByAnchor.get(aliasKey);
                if (anchorEntityId != null) {
                    union(parent, anchorEntityId, entity.getId());
                }
            }
            for (String aliasName : variants.aliasNames()) {
                for (Long anchorEntityId : entityIdsByAnchorName.getOrDefault(aliasName, List.of())) {
                    union(parent, anchorEntityId, entity.getId());
                }
            }
        }

        Map<Long, List<SuperAgentKgEntity>> grouped = new LinkedHashMap<>();
        for (SuperAgentKgEntity entity : entityById.values()) {
            grouped.computeIfAbsent(find(parent, entity.getId()), ignored -> new ArrayList<>()).add(entity);
        }
        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> groupByEntityId = new LinkedHashMap<>();
        for (List<SuperAgentKgEntity> groupEntities : grouped.values()) {
            if (groupEntities.isEmpty()) {
                continue;
            }
            String name = chooseCanonicalEntityName(groupEntities);
            String entityType = canonicalEntityType(groupEntities.get(0));
            String key = canonicalEntityGroupKey(groupEntities.get(0), name);
            Set<Long> entityIds = groupEntities.stream()
                .map(SuperAgentKgEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<Long> documentIds = groupEntities.stream()
                .map(SuperAgentKgEntity::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<Long> taskIds = groupEntities.stream()
                .map(SuperAgentKgEntity::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            LinkedHashSet<String> variants = new LinkedHashSet<>();
            for (SuperAgentKgEntity entity : groupEntities) {
                variants.addAll(variantsByEntityId.get(entity.getId()).allKeys());
            }
            GraphRagCrossDocumentIndex.QualityProfile qualityProfile = canonicalEntityQualityProfile(
                name,
                groupEntities,
                documentIds,
                variants
            );
            double explicitRankBoost = groupEntities.stream()
                .mapToDouble(this::entityRankBoost)
                .max()
                .orElse(0D);
            double rankScore = Math.max(explicitRankBoost, qualityProfile.score());
            GraphRagCrossDocumentIndex.CanonicalEntityGroup group = new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
                key,
                name,
                entityType,
                entityIds,
                documentIds,
                taskIds,
                List.copyOf(variants),
                rounded(rankScore),
                qualityProfile
            );
            for (Long entityId : entityIds) {
                groupByEntityId.put(entityId, group);
            }
        }
        return groupByEntityId;
    }

    private Map<Long, GraphRagCrossDocumentIndex.RelationGroup> buildRelationGroups(Collection<SuperAgentKgRelation> relations,
                                                                                   List<SuperAgentKgEvidence> evidences,
                                                                                   Map<Long, SuperAgentKgEntity> entityMap,
                                                                                   Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalGroups) {
        if (CollUtil.isEmpty(relations)) {
            return Map.of();
        }
        Map<Long, Set<Long>> evidenceIdsByRelationId = new LinkedHashMap<>();
        Map<Long, Set<Long>> documentIdsByRelationId = new LinkedHashMap<>();
        for (SuperAgentKgEvidence evidence : safeList(evidences)) {
            if (evidence == null || evidence.getRelationId() == null || !isGraphEvidenceUsable(evidence)) {
                continue;
            }
            if (evidence.getId() != null) {
                evidenceIdsByRelationId.computeIfAbsent(evidence.getRelationId(), ignored -> new LinkedHashSet<>())
                    .add(evidence.getId());
            }
            if (evidence.getDocumentId() != null) {
                documentIdsByRelationId.computeIfAbsent(evidence.getRelationId(), ignored -> new LinkedHashSet<>())
                    .add(evidence.getDocumentId());
            }
        }

        Map<String, RelationGroupAccumulator> accumulators = new LinkedHashMap<>();
        for (SuperAgentKgRelation relation : relations) {
            if (relation == null || relation.getId() == null) {
                continue;
            }
            SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
            SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
            if (source == null || target == null || !isGraphSearchEntityUsable(source) || !isGraphSearchEntityUsable(target)) {
                continue;
            }
            String groupKey = relationGroupKey(relation, source, target, canonicalGroups);
            RelationGroupAccumulator accumulator = accumulators.computeIfAbsent(
                groupKey,
                key -> new RelationGroupAccumulator(
                    key,
                    canonicalEntityGroupKey(source, canonicalGroups),
                    canonicalEntityGroupKey(target, canonicalGroups),
                    StrUtil.blankToDefault(relation.getRelationType(), "ASSOCIATED_WITH").trim().toUpperCase(Locale.ROOT)
                )
            );
            accumulator.addRelation(relation);
            Set<Long> relationEvidenceIds = evidenceIdsByRelationId.get(relation.getId());
            accumulator.addEvidenceIds(relationEvidenceIds);
            accumulator.addEvidenceCount(relation.getId(), relationEvidenceIds);
            accumulator.addDocumentIds(documentIdsByRelationId.get(relation.getId()));
            if (relation.getDocumentId() != null) {
                accumulator.addDocumentId(relation.getDocumentId());
            }
            accumulator.addRankScore(relationRankBoost(relation, source, target));
            accumulator.addRelationMetadata(readMap(relation.getMetadataJson()));
        }

        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> groupByRelationId = new LinkedHashMap<>();
        for (RelationGroupAccumulator accumulator : accumulators.values()) {
            GraphRagCrossDocumentIndex.QualityProfile qualityProfile = relationGroupQualityProfile(accumulator);
            GraphRagCrossDocumentIndex.RelationGroup group = accumulator.toGroup(qualityProfile);
            for (Long relationId : accumulator.relationIds()) {
                groupByRelationId.put(relationId, group);
            }
        }
        return groupByRelationId;
    }

    private GraphRagCrossDocumentIndex withGlobalRank(
        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByEntityId,
        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> relationByRelationId
    ) {
        if (canonicalByEntityId.isEmpty()) {
            return withCommunities(new GraphRagCrossDocumentIndex(canonicalByEntityId, relationByRelationId));
        }
        Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByKey = distinctCanonicalGroups(canonicalByEntityId);
        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationByKey = distinctRelationGroups(relationByRelationId);
        Map<String, GraphRagCrossDocumentIndex.RankProfile> canonicalRankProfiles = calculateCanonicalRankProfiles(canonicalByKey, relationByKey);
        if (canonicalRankProfiles.isEmpty()) {
            return withCommunities(new GraphRagCrossDocumentIndex(canonicalByEntityId, relationByRelationId));
        }

        Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> rankedCanonicalByKey = new LinkedHashMap<>();
        for (GraphRagCrossDocumentIndex.CanonicalEntityGroup group : canonicalByKey.values()) {
            GraphRagCrossDocumentIndex.RankProfile rankProfile = canonicalRankProfiles.getOrDefault(
                group.key(),
                GraphRagCrossDocumentIndex.RankProfile.empty()
            );
            rankedCanonicalByKey.put(group.key(), new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
                group.key(),
                group.name(),
                group.entityType(),
                group.entityIds(),
                group.documentIds(),
                group.taskIds(),
                group.variants(),
                stableCanonicalRankScore(group, rankProfile),
                group.qualityProfile(),
                rankProfile
            ));
        }

        Map<String, GraphRagCrossDocumentIndex.RelationGroup> rankedRelationByKey = new LinkedHashMap<>();
        Map<String, GraphRagCrossDocumentIndex.RankProfile> relationRankProfiles = calculateRelationRankProfiles(
            relationByKey,
            canonicalRankProfiles
        );
        for (GraphRagCrossDocumentIndex.RelationGroup group : relationByKey.values()) {
            GraphRagCrossDocumentIndex.RankProfile rankProfile = relationRankProfiles.getOrDefault(
                group.key(),
                GraphRagCrossDocumentIndex.RankProfile.empty()
            );
            rankedRelationByKey.put(group.key(), new GraphRagCrossDocumentIndex.RelationGroup(
                group.key(),
                group.sourceGroupKey(),
                group.targetGroupKey(),
                group.relationType(),
                group.relationIds(),
                group.evidenceIds(),
                group.documentIds(),
                group.evidenceCountByRelationId(),
                stableRelationRankScore(group, rankProfile),
                group.qualityProfile(),
                rankProfile
            ));
        }

        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> rankedCanonicalByEntityId = new LinkedHashMap<>();
        for (Map.Entry<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> entry : canonicalByEntityId.entrySet()) {
            rankedCanonicalByEntityId.put(
                entry.getKey(),
                rankedCanonicalByKey.getOrDefault(entry.getValue().key(), entry.getValue())
            );
        }
        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> rankedRelationByRelationId = new LinkedHashMap<>();
        for (Map.Entry<Long, GraphRagCrossDocumentIndex.RelationGroup> entry : relationByRelationId.entrySet()) {
            rankedRelationByRelationId.put(
                entry.getKey(),
                rankedRelationByKey.getOrDefault(entry.getValue().key(), entry.getValue())
            );
        }
        return withCommunities(new GraphRagCrossDocumentIndex(rankedCanonicalByEntityId, rankedRelationByRelationId));
    }

    private GraphRagCrossDocumentIndex withCommunities(GraphRagCrossDocumentIndex index) {
        if (index == null || !index.hasRelationGroups()) {
            return index == null ? GraphRagCrossDocumentIndex.empty() : index;
        }
        Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByKey = index.distinctCanonicalGroups();
        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationByKey = index.distinctRelationGroups();
        if (canonicalByKey.isEmpty() || relationByKey.isEmpty()) {
            return index;
        }

        Map<String, String> parent = new LinkedHashMap<>();
        for (String canonicalKey : canonicalByKey.keySet()) {
            parent.put(canonicalKey, canonicalKey);
        }
        for (GraphRagCrossDocumentIndex.RelationGroup relationGroup : relationByKey.values()) {
            if (StrUtil.isBlank(relationGroup.sourceGroupKey())
                || StrUtil.isBlank(relationGroup.targetGroupKey())
                || !parent.containsKey(relationGroup.sourceGroupKey())
                || !parent.containsKey(relationGroup.targetGroupKey())) {
                continue;
            }
            unionKey(parent, relationGroup.sourceGroupKey(), relationGroup.targetGroupKey());
        }

        Map<String, CommunityAccumulator> accumulators = new LinkedHashMap<>();
        for (GraphRagCrossDocumentIndex.RelationGroup relationGroup : relationByKey.values()) {
            String componentKey = communityComponentKey(parent, relationGroup, canonicalByKey);
            if (StrUtil.isBlank(componentKey)) {
                continue;
            }
            CommunityAccumulator accumulator = accumulators.computeIfAbsent(componentKey, CommunityAccumulator::new);
            accumulator.addRelationGroup(relationGroup);
            GraphRagCrossDocumentIndex.CanonicalEntityGroup source = canonicalByKey.get(relationGroup.sourceGroupKey());
            GraphRagCrossDocumentIndex.CanonicalEntityGroup target = canonicalByKey.get(relationGroup.targetGroupKey());
            accumulator.addCanonicalGroup(source);
            accumulator.addCanonicalGroup(target);
        }

        List<GraphRagCrossDocumentIndex.CrossDocumentCommunity> communities = accumulators.values().stream()
            .map(CommunityAccumulator::toCommunity)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingDouble(GraphRagCrossDocumentIndex.CrossDocumentCommunity::rankScore).reversed()
                .thenComparing(GraphRagCrossDocumentIndex.CrossDocumentCommunity::key))
            .toList();
        LinkedHashMap<String, GraphRagCrossDocumentIndex.CrossDocumentCommunity> communityByKey = new LinkedHashMap<>();
        LinkedHashMap<String, GraphRagCrossDocumentIndex.CrossDocumentCommunity> communityByRelationGroupKey = new LinkedHashMap<>();
        for (int indexNo = 0; indexNo < communities.size(); indexNo++) {
            GraphRagCrossDocumentIndex.CrossDocumentCommunity community = communities.get(indexNo);
            GraphRagCrossDocumentIndex.CrossDocumentCommunity rankedCommunity = new GraphRagCrossDocumentIndex.CrossDocumentCommunity(
                community.id(),
                community.key(),
                community.title(),
                community.summary(),
                community.canonicalGroupKeys(),
                community.relationGroupKeys(),
                community.evidenceIds(),
                community.documentIds(),
                community.reportProfile(),
                community.rankScore(),
                community.qualityProfile(),
                new GraphRagCrossDocumentIndex.RankProfile(
                    community.rankProfile().pagerank(),
                    community.rankProfile().rankBoost(),
                    indexNo + 1,
                    community.rankProfile().degree(),
                    community.rankProfile().inDegree(),
                    community.rankProfile().outDegree(),
                    community.rankProfile().weightedDegree()
                )
            );
            communityByKey.put(rankedCommunity.key(), rankedCommunity);
            for (String relationGroupKey : rankedCommunity.relationGroupKeys()) {
                communityByRelationGroupKey.put(relationGroupKey, rankedCommunity);
            }
        }
        return new GraphRagCrossDocumentIndex(
            index.canonicalGroupByEntityId(),
            index.relationGroupByRelationId(),
            communityByKey,
            communityByRelationGroupKey
        );
    }

    private String communityComponentKey(Map<String, String> parent,
                                         GraphRagCrossDocumentIndex.RelationGroup relationGroup,
                                         Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByKey) {
        String sourceKey = relationGroup.sourceGroupKey();
        String targetKey = relationGroup.targetGroupKey();
        if (!parent.containsKey(sourceKey) && !parent.containsKey(targetKey)) {
            return "";
        }
        String representative = parent.containsKey(sourceKey)
            ? keyByRoot(parent, findKey(parent, sourceKey), canonicalByKey)
            : keyByRoot(parent, findKey(parent, targetKey), canonicalByKey);
        if (StrUtil.isBlank(representative)) {
            representative = sourceKey + "|" + targetKey;
        }
        return "xdoc-community:" + normalizeCanonicalVariant(representative);
    }

    private String keyByRoot(Map<String, String> parent,
                             String root,
                             Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByKey) {
        return parent.entrySet().stream()
            .filter(entry -> Objects.equals(findKey(parent, entry.getKey()), root))
            .map(Map.Entry::getKey)
            .filter(canonicalByKey::containsKey)
            .sorted(Comparator.comparingDouble((String key) -> canonicalByKey.get(key).rankScore()).reversed()
                .thenComparing(key -> StrUtil.blankToDefault(canonicalByKey.get(key).name(), key)))
            .findFirst()
            .orElse("");
    }

    private Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> distinctCanonicalGroups(
        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByEntityId
    ) {
        LinkedHashMap<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> result = new LinkedHashMap<>();
        for (GraphRagCrossDocumentIndex.CanonicalEntityGroup group : canonicalByEntityId.values()) {
            if (group != null) {
                result.putIfAbsent(group.key(), group);
            }
        }
        return result;
    }

    private Map<String, GraphRagCrossDocumentIndex.RelationGroup> distinctRelationGroups(
        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> relationByRelationId
    ) {
        LinkedHashMap<String, GraphRagCrossDocumentIndex.RelationGroup> result = new LinkedHashMap<>();
        for (GraphRagCrossDocumentIndex.RelationGroup group : relationByRelationId.values()) {
            if (group != null) {
                result.putIfAbsent(group.key(), group);
            }
        }
        return result;
    }

    private Map<String, GraphRagCrossDocumentIndex.RankProfile> calculateCanonicalRankProfiles(
        Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByKey,
        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationByKey
    ) {
        if (canonicalByKey.isEmpty() || relationByKey.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Double>> outgoingWeights = new LinkedHashMap<>();
        Map<String, RankAccumulator> accumulators = new LinkedHashMap<>();
        for (String groupKey : canonicalByKey.keySet()) {
            outgoingWeights.put(groupKey, new LinkedHashMap<>());
            accumulators.put(groupKey, new RankAccumulator());
        }

        int edgeCount = 0;
        for (GraphRagCrossDocumentIndex.RelationGroup relationGroup : relationByKey.values()) {
            String sourceKey = relationGroup.sourceGroupKey();
            String targetKey = relationGroup.targetGroupKey();
            if (StrUtil.isBlank(sourceKey)
                || StrUtil.isBlank(targetKey)
                || Objects.equals(sourceKey, targetKey)
                || !canonicalByKey.containsKey(sourceKey)
                || !canonicalByKey.containsKey(targetKey)) {
                continue;
            }
            double edgeWeight = relationGraphWeight(relationGroup);
            addRankEdge(outgoingWeights, sourceKey, targetKey, edgeWeight);
            accumulators.get(sourceKey).addOut(edgeWeight);
            accumulators.get(targetKey).addIn(edgeWeight);
            addRankEdge(outgoingWeights, targetKey, sourceKey, edgeWeight);
            accumulators.get(targetKey).addOut(edgeWeight);
            accumulators.get(sourceKey).addIn(edgeWeight);
            edgeCount += 2;
        }
        if (edgeCount == 0) {
            return Map.of();
        }

        Map<String, Double> pageranks = calculatePagerank(canonicalByKey.keySet(), outgoingWeights);
        double maxPagerank = pageranks.values().stream()
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0D);
        List<String> rankedKeys = pageranks.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .toList();
        Map<String, Integer> rankPositions = new LinkedHashMap<>();
        for (int index = 0; index < rankedKeys.size(); index++) {
            rankPositions.put(rankedKeys.get(index), index + 1);
        }

        Map<String, GraphRagCrossDocumentIndex.RankProfile> result = new LinkedHashMap<>();
        for (String groupKey : canonicalByKey.keySet()) {
            double pagerank = pageranks.getOrDefault(groupKey, 0D);
            double rankBoost = maxPagerank <= 0D ? 0D : Math.sqrt(pagerank / maxPagerank);
            RankAccumulator accumulator = accumulators.getOrDefault(groupKey, RankAccumulator.empty());
            result.put(groupKey, new GraphRagCrossDocumentIndex.RankProfile(
                rounded(pagerank),
                rounded(clamp(rankBoost)),
                rankPositions.getOrDefault(groupKey, 0),
                accumulator.degree(),
                accumulator.inDegree(),
                accumulator.outDegree(),
                rounded(accumulator.weightedDegree())
            ));
        }
        return result;
    }

    private Map<String, GraphRagCrossDocumentIndex.RankProfile> calculateRelationRankProfiles(
        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationByKey,
        Map<String, GraphRagCrossDocumentIndex.RankProfile> canonicalRankProfiles
    ) {
        if (relationByKey.isEmpty() || canonicalRankProfiles.isEmpty()) {
            return Map.of();
        }
        double maxRelationGraphWeight = relationByKey.values().stream()
            .mapToDouble(this::relationGraphWeight)
            .max()
            .orElse(0D);
        List<RelationRankCandidate> candidates = new ArrayList<>();
        for (GraphRagCrossDocumentIndex.RelationGroup group : relationByKey.values()) {
            GraphRagCrossDocumentIndex.RankProfile sourceRank = canonicalRankProfiles.get(group.sourceGroupKey());
            GraphRagCrossDocumentIndex.RankProfile targetRank = canonicalRankProfiles.get(group.targetGroupKey());
            if (sourceRank == null || targetRank == null) {
                continue;
            }
            double normalizedRelationWeight = maxRelationGraphWeight <= 0D
                ? 0D
                : relationGraphWeight(group) / maxRelationGraphWeight;
            double endpointRankBoost = Math.max(sourceRank.rankBoost(), targetRank.rankBoost());
            double qualityScore = group.qualityProfile() == null ? 0D : group.qualityProfile().score();
            double rankBoost = clamp(qualityScore * 0.58D + endpointRankBoost * 0.30D + normalizedRelationWeight * 0.12D);
            double pagerank = (sourceRank.pagerank() + targetRank.pagerank()) / 2D;
            candidates.add(new RelationRankCandidate(
                group.key(),
                new GraphRagCrossDocumentIndex.RankProfile(
                    rounded(pagerank),
                    rounded(rankBoost),
                    0,
                    sourceRank.degree() + targetRank.degree(),
                    targetRank.inDegree(),
                    sourceRank.outDegree(),
                    rounded(sourceRank.weightedDegree() + targetRank.weightedDegree())
                )
            ));
        }
        List<RelationRankCandidate> rankedCandidates = candidates.stream()
            .sorted(Comparator.comparingDouble((RelationRankCandidate candidate) -> candidate.rankProfile().rankBoost()).reversed()
                .thenComparing(candidate -> candidate.rankProfile().pagerank(), Comparator.reverseOrder())
                .thenComparing(RelationRankCandidate::groupKey))
            .toList();
        Map<String, GraphRagCrossDocumentIndex.RankProfile> result = new LinkedHashMap<>();
        for (int index = 0; index < rankedCandidates.size(); index++) {
            RelationRankCandidate candidate = rankedCandidates.get(index);
            GraphRagCrossDocumentIndex.RankProfile profile = candidate.rankProfile();
            result.put(candidate.groupKey(), new GraphRagCrossDocumentIndex.RankProfile(
                profile.pagerank(),
                profile.rankBoost(),
                index + 1,
                profile.degree(),
                profile.inDegree(),
                profile.outDegree(),
                profile.weightedDegree()
            ));
        }
        return result;
    }

    private Map<String, Double> calculatePagerank(Set<String> nodeKeys,
                                                  Map<String, Map<String, Double>> outgoingWeights) {
        int nodeCount = nodeKeys.size();
        if (nodeCount == 0) {
            return Map.of();
        }
        double initialScore = 1D / nodeCount;
        Map<String, Double> ranks = new LinkedHashMap<>();
        for (String nodeKey : nodeKeys) {
            ranks.put(nodeKey, initialScore);
        }
        double damping = 0.85D;
        for (int iteration = 0; iteration < 30; iteration++) {
            Map<String, Double> next = new LinkedHashMap<>();
            for (String nodeKey : nodeKeys) {
                next.put(nodeKey, (1D - damping) / nodeCount);
            }
            double danglingMass = 0D;
            for (String sourceKey : nodeKeys) {
                Map<String, Double> outgoing = outgoingWeights.getOrDefault(sourceKey, Map.of());
                double totalWeight = outgoing.values().stream().mapToDouble(Double::doubleValue).sum();
                if (outgoing.isEmpty() || totalWeight <= 0D) {
                    danglingMass += ranks.getOrDefault(sourceKey, 0D);
                    continue;
                }
                double sourceRank = ranks.getOrDefault(sourceKey, 0D);
                for (Map.Entry<String, Double> edge : outgoing.entrySet()) {
                    if (!nodeKeys.contains(edge.getKey())) {
                        continue;
                    }
                    double contribution = damping * sourceRank * (edge.getValue() / totalWeight);
                    next.merge(edge.getKey(), contribution, Double::sum);
                }
            }
            if (danglingMass > 0D) {
                double danglingContribution = damping * danglingMass / nodeCount;
                for (String nodeKey : nodeKeys) {
                    next.merge(nodeKey, danglingContribution, Double::sum);
                }
            }
            ranks = next;
        }
        return ranks;
    }

    private void addRankEdge(Map<String, Map<String, Double>> outgoingWeights,
                             String sourceKey,
                             String targetKey,
                             double weight) {
        outgoingWeights.computeIfAbsent(sourceKey, ignored -> new LinkedHashMap<>())
            .merge(targetKey, Math.max(0.01D, weight), Double::sum);
    }

    private double relationGraphWeight(GraphRagCrossDocumentIndex.RelationGroup group) {
        if (group == null) {
            return 0D;
        }
        double qualityScore = group.qualityProfile() == null ? 0D : group.qualityProfile().score();
        double weight = 0.18D
            + qualityScore * 0.46D
            + Math.min(0.14D, group.evidenceCount() * 0.035D)
            + Math.min(0.12D, group.documentCount() * 0.06D)
            + Math.min(0.10D, group.relationCount() * 0.025D);
        return rounded(clamp(weight));
    }

    private double stableCanonicalRankScore(GraphRagCrossDocumentIndex.CanonicalEntityGroup group,
                                            GraphRagCrossDocumentIndex.RankProfile rankProfile) {
        double qualityScore = group.qualityProfile() == null ? 0D : group.qualityProfile().score();
        double crossDocumentSupport = group.documentIds() == null || group.documentIds().size() <= 1 ? 0D : 0.05D;
        double degreeSupport = rankProfile == null ? 0D : Math.min(0.04D, rankProfile.degree() * 0.01D);
        double rankBoost = rankProfile == null ? 0D : rankProfile.rankBoost();
        return rounded(clamp(qualityScore * 0.73D + rankBoost * 0.18D + crossDocumentSupport + degreeSupport));
    }

    private double stableRelationRankScore(GraphRagCrossDocumentIndex.RelationGroup group,
                                           GraphRagCrossDocumentIndex.RankProfile rankProfile) {
        double qualityScore = group.qualityProfile() == null ? 0D : group.qualityProfile().score();
        double crossDocumentSupport = group.documentCount() <= 1 ? 0D : 0.06D;
        double evidenceSupport = Math.min(0.05D, group.evidenceCount() * 0.01D);
        double rankBoost = rankProfile == null ? 0D : rankProfile.rankBoost();
        return rounded(clamp(qualityScore * 0.70D + rankBoost * 0.17D + crossDocumentSupport + evidenceSupport));
    }

    private Set<String> canonicalEntityVariantKeys(SuperAgentKgEntity entity) {
        return canonicalEntityVariants(entity).allKeys();
    }

    private CanonicalEntityVariants canonicalEntityVariants(SuperAgentKgEntity entity) {
        LinkedHashSet<String> anchorKeys = new LinkedHashSet<>();
        LinkedHashSet<String> aliasKeys = new LinkedHashSet<>();
        LinkedHashSet<String> anchorNames = new LinkedHashSet<>();
        LinkedHashSet<String> aliasNames = new LinkedHashSet<>();
        if (!isGraphSearchEntityUsable(entity)) {
            return CanonicalEntityVariants.empty();
        }
        String entityType = canonicalEntityType(entity);
        addCanonicalNameVariant(anchorKeys, anchorNames, entityType, entity.getName());
        addCanonicalNameVariant(anchorKeys, anchorNames, entityType, entity.getNormalizedName());
        Map<String, Object> metadata = readMap(entity.getMetadataJson());
        addCanonicalNameVariant(
            anchorKeys,
            anchorNames,
            entityType,
            stringValue(metadata.get("entityResolutionCanonicalName"))
        );
        for (String alias : readStringList(metadata.get("aliases"))) {
            addCanonicalAliasVariant(aliasKeys, aliasNames, entityType, alias);
        }
        return CanonicalEntityVariants.of(anchorKeys, aliasKeys, anchorNames, aliasNames);
    }

    private void addCanonicalNameVariant(Set<String> variants,
                                         Set<String> names,
                                         String entityType,
                                         String value) {
        String normalized = normalizeCanonicalVariant(value);
        if (isCanonicalVariantUsable(normalized)) {
            names.add(normalized);
            variants.add(entityType + ":" + normalized);
            if (isCrossTypeCanonicalVariantUsable(normalized)) {
                variants.add("NAME:" + normalized);
            }
        }
    }

    private void addCanonicalAliasVariant(Set<String> variants,
                                          Set<String> names,
                                          String entityType,
                                          String value) {
        String normalized = normalizeCanonicalVariant(value);
        if (!isCanonicalVariantUsable(normalized)) {
            return;
        }
        names.add(normalized);
        variants.add(entityType + ":" + normalized);
        if (isCrossTypeCanonicalVariantUsable(normalized)) {
            variants.add("NAME:" + normalized);
        }
    }

    private boolean isCrossTypeCanonicalVariantUsable(String normalized) {
        return isCanonicalVariantUsable(normalized)
            && normalized.length() >= 3
            && normalized.chars().anyMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'));
    }

    private record CanonicalEntityVariants(Set<String> anchorKeys,
                                           Set<String> aliasKeys,
                                           Set<String> anchorNames,
                                           Set<String> aliasNames,
                                           Set<String> allKeys) {

        private static CanonicalEntityVariants empty() {
            return new CanonicalEntityVariants(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        private static CanonicalEntityVariants of(Set<String> anchorKeys,
                                                  Set<String> aliasKeys,
                                                  Set<String> anchorNames,
                                                  Set<String> aliasNames) {
            LinkedHashSet<String> allKeys = new LinkedHashSet<>(anchorKeys);
            allKeys.addAll(aliasKeys);
            return new CanonicalEntityVariants(
                Collections.unmodifiableSet(new LinkedHashSet<>(anchorKeys)),
                Collections.unmodifiableSet(new LinkedHashSet<>(aliasKeys)),
                Collections.unmodifiableSet(new LinkedHashSet<>(anchorNames)),
                Collections.unmodifiableSet(new LinkedHashSet<>(aliasNames)),
                Collections.unmodifiableSet(allKeys)
            );
        }
    }

    private String chooseCanonicalEntityName(List<SuperAgentKgEntity> entities) {
        for (SuperAgentKgEntity entity : entities) {
            String advisedName = stringValue(readMap(entity.getMetadataJson()).get("entityResolutionCanonicalName"));
            if (StrUtil.isNotBlank(advisedName)) {
                return advisedName;
            }
        }
        return entities.stream()
            .sorted(Comparator.comparingDouble(this::entityRankBoost).reversed())
            .map(SuperAgentKgEntity::getName)
            .filter(StrUtil::isNotBlank)
            .findFirst()
            .orElse("unknown");
    }

    private String canonicalEntityGroupKey(SuperAgentKgEntity representative, String name) {
        String entityType = canonicalEntityType(representative);
        String normalizedName = normalizeCanonicalVariant(name);
        String key = entityType + ":" + normalizedName;
        if (!isCanonicalVariantUsable(normalizedName)) {
            Long entityId = representative == null ? null : representative.getId();
            key += "#" + (entityId == null ? "unknown" : entityId);
        }
        return key;
    }

    private boolean isTechnicalEntityName(String normalizedName) {
        if (StrUtil.isBlank(normalizedName)) {
            return false;
        }
        if (TECHNICAL_ENTITY_LABELS.contains(normalizedName)) {
            return true;
        }
        if (normalizedName.startsWith("type")) {
            String afterType = normalizedName.substring("type".length());
            if (TECHNICAL_ENTITY_LABELS.contains(afterType) || hasTechnicalLabelPrefix(afterType)) {
                return true;
            }
        }
        return hasTechnicalLabelPrefix(normalizedName);
    }

    private boolean hasTechnicalLabelPrefix(String normalizedName) {
        for (String label : TECHNICAL_ENTITY_LABELS) {
            if (!normalizedName.startsWith(label) || normalizedName.length() <= label.length()) {
                continue;
            }
            char next = normalizedName.charAt(label.length());
            boolean alphaNumericPayload = (next >= 'a' && next <= 'z') || (next >= '0' && next <= '9');
            if (!alphaNumericPayload) {
                return true;
            }
        }
        return false;
    }

    private GraphRagCrossDocumentIndex.QualityProfile canonicalEntityQualityProfile(String canonicalName,
                                                                                   List<SuperAgentKgEntity> entities,
                                                                                   Set<Long> documentIds,
                                                                                   Set<String> variants) {
        LinkedHashSet<String> qualityReasons = new LinkedHashSet<>();
        LinkedHashSet<String> noiseReasons = new LinkedHashSet<>();
        double score = 0.36D;

        if (CollUtil.isNotEmpty(entities)) {
            score += Math.min(0.16D, entities.size() * 0.04D);
            qualityReasons.add("entityCount=" + entities.size());
        }
        if (documentIds != null && documentIds.size() > 1) {
            score += 0.16D;
            qualityReasons.add("crossDocument");
        }
        if (variants != null && variants.size() > 1) {
            score += 0.08D;
            qualityReasons.add("aliasOrVariantSupport");
        }

        LinkedHashSet<String> sources = new LinkedHashSet<>();
        int evidenceCount = 0;
        double confidence = 0D;
        for (SuperAgentKgEntity entity : safeList(entities)) {
            sources.addAll(normalizedSources(entityCandidateSources(entity)));
            Map<String, Object> metadata = readMap(entity.getMetadataJson());
            sources.addAll(normalizedSources(readStringList(metadata.get("extractorSources"))));
            evidenceCount += readStringList(metadata.get("evidenceIds")).size();
            confidence = Math.max(confidence, numberValue(metadata.get("confidence"), 0D));
        }
        if (evidenceCount > 0) {
            score += Math.min(0.12D, evidenceCount * 0.03D);
            qualityReasons.add("evidenceLinked");
        }
        if (confidence > 0D) {
            score += Math.min(0.1D, confidence * 0.1D);
            qualityReasons.add("confidence");
        }
        if (!Collections.disjoint(sources, STRONG_ENTITY_SOURCES)) {
            score += 0.1D;
            qualityReasons.add("strongExtractorSource");
        }
        if (!Collections.disjoint(sources, WEAK_ENTITY_SOURCES)) {
            score -= 0.08D;
            noiseReasons.add("weakEntitySource");
        }
        if (looksLikeSentenceFragment(canonicalName)) {
            score -= 0.22D;
            noiseReasons.add("sentenceLikeName");
        }
        if (isLongOrLowSignalName(canonicalName)) {
            score -= 0.12D;
            noiseReasons.add("lowSignalName");
        }
        return new GraphRagCrossDocumentIndex.QualityProfile(
            rounded(clamp(score)),
            List.copyOf(qualityReasons),
            List.copyOf(noiseReasons)
        );
    }

    private GraphRagCrossDocumentIndex.QualityProfile relationGroupQualityProfile(RelationGroupAccumulator accumulator) {
        LinkedHashSet<String> qualityReasons = new LinkedHashSet<>();
        LinkedHashSet<String> noiseReasons = new LinkedHashSet<>();
        double score = 0.28D;

        if (accumulator.relationIds.size() > 1) {
            score += Math.min(0.12D, accumulator.relationIds.size() * 0.04D);
            qualityReasons.add("multiRelationSupport");
        }
        if (accumulator.evidenceIds.size() > 0) {
            score += Math.min(0.2D, accumulator.evidenceIds.size() * 0.06D);
            qualityReasons.add("groundedEvidence");
        }
        else {
            score -= 0.18D;
            noiseReasons.add("missingEvidence");
        }
        if (accumulator.documentIds.size() > 1) {
            score += 0.14D;
            qualityReasons.add("crossDocument");
        }
        if (!Collections.disjoint(accumulator.candidateSources, STRONG_RELATION_SOURCES)
            || !Collections.disjoint(accumulator.extractorSources, STRONG_RELATION_SOURCES)) {
            score += 0.12D;
            qualityReasons.add("strongRelationSource");
        }
        if (accumulator.sourceGroupKey.equals(accumulator.targetGroupKey)) {
            score -= 0.16D;
            noiseReasons.add("sameCanonicalEndpoint");
        }
        double explicitRank = clamp(accumulator.rankScore);
        if (explicitRank > 0D) {
            score = Math.max(score, explicitRank);
            qualityReasons.add("explicitRankBoost");
        }
        return new GraphRagCrossDocumentIndex.QualityProfile(
            rounded(clamp(score)),
            List.copyOf(qualityReasons),
            List.copyOf(noiseReasons)
        );
    }

    private Set<String> normalizedSources(Collection<String> values) {
        if (CollUtil.isEmpty(values)) {
            return Set.of();
        }
        return values.stream()
            .filter(StrUtil::isNotBlank)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean looksLikeSentenceFragment(String name) {
        String value = StrUtil.blankToDefault(name, "").trim();
        if (StrUtil.isBlank(value)) {
            return true;
        }
        int codePoints = value.codePointCount(0, value.length());
        if (value.contains("，")
            || value.contains("。")
            || value.contains("；")
            || value.contains("：")
            || value.contains("、")
            || value.contains("->")
            || value.contains("=>")) {
            return true;
        }
        if (codePoints >= 36) {
            return true;
        }
        return containsWhitespace(value) && containsCjk(value) && codePoints >= 24;
    }

    private boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }

    private boolean containsCjk(String value) {
        return value.chars().anyMatch(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN);
    }

    private boolean isLongOrLowSignalName(String name) {
        String normalized = normalizeCanonicalVariant(name);
        if (StrUtil.isBlank(normalized)) {
            return true;
        }
        int codePoints = normalized.codePointCount(0, normalized.length());
        return codePoints < 2 || codePoints > 30;
    }

    private double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private void union(Map<Long, Long> parent, Long left, Long right) {
        Long leftRoot = find(parent, left);
        Long rightRoot = find(parent, right);
        if (!Objects.equals(leftRoot, rightRoot)) {
            parent.put(rightRoot, leftRoot);
        }
    }

    private Long find(Map<Long, Long> parent, Long entityId) {
        if (entityId == null) {
            return null;
        }
        Long current = parent.get(entityId);
        if (current == null) {
            return entityId;
        }
        if (!Objects.equals(current, parent.get(current))) {
            current = find(parent, current);
            parent.put(entityId, current);
        }
        return current;
    }

    private void unionKey(Map<String, String> parent, String left, String right) {
        String leftRoot = findKey(parent, left);
        String rightRoot = findKey(parent, right);
        if (StrUtil.isBlank(leftRoot) || StrUtil.isBlank(rightRoot) || Objects.equals(leftRoot, rightRoot)) {
            return;
        }
        parent.put(rightRoot, leftRoot);
    }

    private String findKey(Map<String, String> parent, String key) {
        if (StrUtil.isBlank(key)) {
            return "";
        }
        String current = parent.get(key);
        if (StrUtil.isBlank(current)) {
            return key;
        }
        if (!Objects.equals(current, parent.get(current))) {
            current = findKey(parent, current);
            parent.put(key, current);
        }
        return current;
    }

    private double rounded(double value) {
        return BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private <T> List<T> safeList(Collection<T> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private static class RelationGroupAccumulator {

        private final String key;
        private final String sourceGroupKey;
        private final String targetGroupKey;
        private final String relationType;
        private final Set<Long> relationIds = new LinkedHashSet<>();
        private final Set<Long> evidenceIds = new LinkedHashSet<>();
        private final Set<Long> documentIds = new LinkedHashSet<>();
        private final Map<Long, Integer> evidenceCountByRelationId = new LinkedHashMap<>();
        private final Set<String> candidateSources = new LinkedHashSet<>();
        private final Set<String> extractorSources = new LinkedHashSet<>();
        private double rankScore;

        private RelationGroupAccumulator(String key, String sourceGroupKey, String targetGroupKey, String relationType) {
            this.key = key;
            this.sourceGroupKey = sourceGroupKey;
            this.targetGroupKey = targetGroupKey;
            this.relationType = relationType;
        }

        private void addRelation(SuperAgentKgRelation relation) {
            if (relation == null || relation.getId() == null) {
                return;
            }
            relationIds.add(relation.getId());
        }

        private void addEvidenceIds(Collection<Long> values) {
            if (CollUtil.isNotEmpty(values)) {
                evidenceIds.addAll(values);
            }
        }

        private void addEvidenceCount(Long relationId, Collection<Long> values) {
            if (relationId == null) {
                return;
            }
            evidenceCountByRelationId.put(relationId, values == null ? 0 : values.size());
        }

        private void addDocumentIds(Collection<Long> values) {
            if (CollUtil.isNotEmpty(values)) {
                documentIds.addAll(values);
            }
        }

        private void addDocumentId(Long documentId) {
            if (documentId != null) {
                documentIds.add(documentId);
            }
        }

        private void addRankScore(double value) {
            rankScore = Math.max(rankScore, value);
        }

        private void addRelationMetadata(Map<String, Object> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return;
            }
            candidateSources.addAll(normalizedMetadataList(metadata.get("candidateSources")));
            extractorSources.addAll(normalizedMetadataList(metadata.get("extractorSources")));
            Object sourceMetadata = metadata.get("sourceMetadata");
            if (sourceMetadata instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item instanceof Map<?, ?> map) {
                        addSourceMetadata(map);
                    }
                }
            }
            else if (sourceMetadata instanceof Map<?, ?> map) {
                addSourceMetadata(map);
            }
        }

        private void addSourceMetadata(Map<?, ?> map) {
            candidateSources.addAll(normalizedMetadataList(map.get("candidateSources")));
            extractorSources.addAll(normalizedMetadataList(map.get("extractorSources")));
            String sourceType = stringMetadataValue(map.get("sourceType"));
            if (StrUtil.isNotBlank(sourceType)) {
                candidateSources.add(sourceType.trim().toLowerCase(Locale.ROOT));
                extractorSources.add(sourceType.trim().toLowerCase(Locale.ROOT));
            }
        }

        private List<String> normalizedMetadataList(Object value) {
            if (value == null) {
                return List.of();
            }
            if (value instanceof Collection<?> collection) {
                return collection.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(StrUtil::isNotBlank)
                    .map(item -> item.trim().toLowerCase(Locale.ROOT))
                    .toList();
            }
            String text = String.valueOf(value);
            if (StrUtil.isBlank(text)) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (String item : text.split("[\\n\\r,，;；、|]+")) {
                if (StrUtil.isNotBlank(item)) {
                    result.add(item.trim().toLowerCase(Locale.ROOT));
                }
            }
            return result;
        }

        private String stringMetadataValue(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private Set<Long> relationIds() {
            return relationIds;
        }

        private GraphRagCrossDocumentIndex.RelationGroup toGroup(GraphRagCrossDocumentIndex.QualityProfile qualityProfile) {
            double effectiveRankScore = Math.max(rankScore, qualityProfile == null ? 0D : qualityProfile.score());
            return new GraphRagCrossDocumentIndex.RelationGroup(
                key,
                sourceGroupKey,
                targetGroupKey,
                relationType,
                Set.copyOf(relationIds),
                Set.copyOf(evidenceIds),
                Set.copyOf(documentIds),
                Map.copyOf(evidenceCountByRelationId),
                effectiveRankScore,
                qualityProfile == null ? GraphRagCrossDocumentIndex.QualityProfile.empty() : qualityProfile
            );
        }
    }

    private class CommunityAccumulator {

        private final String key;
        private final Set<String> canonicalGroupKeys = new LinkedHashSet<>();
        private final Set<String> relationGroupKeys = new LinkedHashSet<>();
        private final Set<Long> evidenceIds = new LinkedHashSet<>();
        private final Set<Long> documentIds = new LinkedHashSet<>();
        private final List<String> canonicalNames = new ArrayList<>();
        private final Set<String> relationTypes = new LinkedHashSet<>();
        private final Set<String> qualityReasons = new LinkedHashSet<>();
        private final Set<String> noiseReasons = new LinkedHashSet<>();
        private double qualityScore;
        private double rankScore;
        private double pagerank;
        private double rankBoost;
        private int degree;
        private int inDegree;
        private int outDegree;
        private double weightedDegree;

        private CommunityAccumulator(String key) {
            this.key = key;
        }

        private void addCanonicalGroup(GraphRagCrossDocumentIndex.CanonicalEntityGroup group) {
            if (group == null || StrUtil.isBlank(group.key())) {
                return;
            }
            canonicalGroupKeys.add(group.key());
            if (StrUtil.isNotBlank(group.name()) && !canonicalNames.contains(group.name())) {
                canonicalNames.add(group.name());
            }
            documentIds.addAll(safeSet(group.documentIds()));
            qualityScore = Math.max(qualityScore, group.qualityProfile().score());
            qualityReasons.addAll(group.qualityProfile().qualityReasons());
            noiseReasons.addAll(group.qualityProfile().noiseReasons());
            rankScore = Math.max(rankScore, group.rankScore());
            pagerank = Math.max(pagerank, group.rankProfile().pagerank());
            rankBoost = Math.max(rankBoost, group.rankProfile().rankBoost());
            degree += group.rankProfile().degree();
            inDegree += group.rankProfile().inDegree();
            outDegree += group.rankProfile().outDegree();
            weightedDegree += group.rankProfile().weightedDegree();
        }

        private void addRelationGroup(GraphRagCrossDocumentIndex.RelationGroup group) {
            if (group == null || StrUtil.isBlank(group.key())) {
                return;
            }
            relationGroupKeys.add(group.key());
            relationTypes.add(StrUtil.blankToDefault(group.relationType(), "ASSOCIATED_WITH"));
            evidenceIds.addAll(safeSet(group.evidenceIds()));
            documentIds.addAll(safeSet(group.documentIds()));
            qualityScore = Math.max(qualityScore, group.qualityProfile().score());
            qualityReasons.addAll(group.qualityProfile().qualityReasons());
            noiseReasons.addAll(group.qualityProfile().noiseReasons());
            rankScore = Math.max(rankScore, group.rankScore());
            pagerank = Math.max(pagerank, group.rankProfile().pagerank());
            rankBoost = Math.max(rankBoost, group.rankProfile().rankBoost());
            degree += group.rankProfile().degree();
            inDegree += group.rankProfile().inDegree();
            outDegree += group.rankProfile().outDegree();
            weightedDegree += group.rankProfile().weightedDegree();
        }

        private GraphRagCrossDocumentIndex.CrossDocumentCommunity toCommunity() {
            if (relationGroupKeys.isEmpty() || canonicalGroupKeys.isEmpty()) {
                return null;
            }
            GraphRagCrossDocumentIndex.QualityProfile qualityProfile = communityQualityProfile();
            GraphRagCrossDocumentIndex.ReportProfile reportProfile = communityReportProfile(qualityProfile);
            double effectiveRank = rounded(clamp(
                qualityProfile.score() * 0.64D
                    + Math.min(1D, rankBoost) * 0.20D
                    + Math.min(0.10D, documentIds.size() * 0.025D)
                    + Math.min(0.06D, relationGroupKeys.size() * 0.015D)
            ));
            return new GraphRagCrossDocumentIndex.CrossDocumentCommunity(
                null,
                key,
                communityTitle(),
                communitySummary(reportProfile),
                Set.copyOf(canonicalGroupKeys),
                Set.copyOf(relationGroupKeys),
                Set.copyOf(evidenceIds),
                Set.copyOf(documentIds),
                reportProfile,
                effectiveRank,
                qualityProfile,
                new GraphRagCrossDocumentIndex.RankProfile(
                    rounded(pagerank),
                    rounded(clamp(rankBoost)),
                    0,
                    degree,
                    inDegree,
                    outDegree,
                    rounded(weightedDegree)
                )
            );
        }

        private GraphRagCrossDocumentIndex.QualityProfile communityQualityProfile() {
            LinkedHashSet<String> reasons = new LinkedHashSet<>();
            LinkedHashSet<String> noises = new LinkedHashSet<>(noiseReasons);
            double score = 0.32D;
            if (canonicalGroupKeys.size() > 1) {
                score += Math.min(0.16D, canonicalGroupKeys.size() * 0.03D);
                reasons.add("multiEntityCommunity");
            }
            if (!relationGroupKeys.isEmpty()) {
                score += Math.min(0.18D, relationGroupKeys.size() * 0.035D);
                reasons.add("relationGroupSupport");
            }
            if (!evidenceIds.isEmpty()) {
                score += Math.min(0.18D, evidenceIds.size() * 0.035D);
                reasons.add("groundedEvidence");
            }
            else {
                score -= 0.18D;
                noises.add("missingEvidence");
            }
            if (documentIds.size() > 1) {
                score += 0.16D;
                reasons.add("crossDocument");
            }
            if (qualityScore > 0D) {
                score += Math.min(0.10D, qualityScore * 0.10D);
                reasons.add("memberQuality");
            }
            reasons.addAll(qualityReasons.stream().limit(4).toList());
            return new GraphRagCrossDocumentIndex.QualityProfile(
                rounded(clamp(score)),
                List.copyOf(reasons),
                List.copyOf(noises)
            );
        }

        private String communityTitle() {
            List<String> names = canonicalNames.stream()
                .filter(StrUtil::isNotBlank)
                .limit(3)
                .toList();
            if (names.isEmpty()) {
                return "跨文档图谱社区";
            }
            return "跨文档图谱社区：" + String.join(" / ", names);
        }

        private String communitySummary(GraphRagCrossDocumentIndex.ReportProfile profile) {
            StringBuilder builder = new StringBuilder();
            builder.append("跨文档社区报告：")
                .append("\n- 核心实体：")
                .append(joinOrDefault(profile.coreEntityNames(), "未形成稳定核心实体"))
                .append("\n- 关键关系类型：")
                .append(joinOrDefault(profile.keyRelationTypes(), "未形成稳定关系类型"))
                .append("\n- 覆盖范围：")
                .append(canonicalGroupKeys.size())
                .append(" 个 canonical 实体组、")
                .append(relationGroupKeys.size())
                .append(" 个关系组、")
                .append(evidenceIds.size())
                .append(" 条可追溯证据、")
                .append(documentIds.size())
                .append(" 份文档。")
                .append("\n- 证据边界：")
                .append(joinOrDefault(profile.evidenceBoundaries(), "仅可依据当前 KG evidence 总结"))
                .append("\n- 不可推断：")
                .append(joinOrDefault(profile.cannotInfer(), "未被 KG relation/evidence 支撑的结论不能从该社区报告推出"))
                .append("\n- 报告质量：")
                .append(profile.qualityScore())
                .append("，原因：")
                .append(joinOrDefault(profile.qualityReasons(), "-"));
            return builder.toString();
        }

        private GraphRagCrossDocumentIndex.ReportProfile communityReportProfile(
            GraphRagCrossDocumentIndex.QualityProfile qualityProfile
        ) {
            List<String> coreEntities = canonicalNames.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .limit(8)
                .toList();
            List<String> keyRelationTypes = relationTypes.stream()
                .filter(StrUtil::isNotBlank)
                .map(type -> type.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .limit(8)
                .toList();
            List<String> evidenceBoundaries = new ArrayList<>();
            evidenceBoundaries.add("仅总结 " + evidenceIds.size() + " 条 KG evidence 支撑的实体和关系。");
            evidenceBoundaries.add("覆盖 " + documentIds.size() + " 份文档，不能代表未入库或未命中的文档。");
            evidenceBoundaries.add("关系范围限定在 " + relationGroupKeys.size() + " 个 relation group 内。");
            List<String> cannotInfer = new ArrayList<>();
            cannotInfer.add("未被 KG relation/evidence 明确支撑的主体、职责、因果和决策结论不能推出。");
            LinkedHashSet<String> reportReasons = new LinkedHashSet<>();
            if (!coreEntities.isEmpty()) {
                reportReasons.add("coreEntityCoverage");
            }
            if (!keyRelationTypes.isEmpty()) {
                reportReasons.add("relationTypeCoverage");
            }
            if (!evidenceIds.isEmpty()) {
                reportReasons.add("groundedEvidence");
            }
            if (documentIds.size() > 1) {
                reportReasons.add("crossDocumentCoverage");
            }
            reportReasons.add("cannotInferBoundary");
            if (qualityProfile != null) {
                reportReasons.addAll(qualityProfile.qualityReasons().stream().limit(5).toList());
            }
            double reportQuality = rounded(clamp(
                (qualityProfile == null ? 0D : qualityProfile.score()) * 0.58D
                    + Math.min(0.16D, coreEntities.size() * 0.025D)
                    + Math.min(0.12D, keyRelationTypes.size() * 0.02D)
                    + Math.min(0.08D, evidenceIds.size() * 0.012D)
                    + (documentIds.size() > 1 ? 0.06D : 0D)
            ));
            return new GraphRagCrossDocumentIndex.ReportProfile(
                "java.cross_document_report.extractive.v1",
                coreEntities,
                keyRelationTypes,
                evidenceBoundaries,
                cannotInfer.stream().distinct().limit(6).toList(),
                reportQuality,
                List.copyOf(reportReasons)
            );
        }

        private String joinOrDefault(List<String> values, String defaultValue) {
            List<String> filtered = safeList(values).stream()
                .filter(StrUtil::isNotBlank)
                .toList();
            if (filtered.isEmpty()) {
                return defaultValue;
            }
            return String.join("；", filtered);
        }

        private <T> Set<T> safeSet(Set<T> values) {
            return values == null ? Set.of() : values;
        }
    }

    private static class RankAccumulator {

        private int inDegree;
        private int outDegree;
        private double weightedDegree;

        private static RankAccumulator empty() {
            return new RankAccumulator();
        }

        private void addIn(double weight) {
            inDegree++;
            weightedDegree += Math.max(0D, weight);
        }

        private void addOut(double weight) {
            outDegree++;
            weightedDegree += Math.max(0D, weight);
        }

        private int degree() {
            return inDegree + outDegree;
        }

        private int inDegree() {
            return inDegree;
        }

        private int outDegree() {
            return outDegree;
        }

        private double weightedDegree() {
            return weightedDegree;
        }
    }

    private record RelationRankCandidate(String groupKey,
                                         GraphRagCrossDocumentIndex.RankProfile rankProfile) {
    }
}
