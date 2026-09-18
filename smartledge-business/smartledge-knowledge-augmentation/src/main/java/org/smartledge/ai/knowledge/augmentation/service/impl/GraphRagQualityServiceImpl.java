package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEntity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEvidence;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelation;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEntityMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgEvidenceMapper;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentKgRelationMapper;
import org.smartledge.ai.knowledge.augmentation.model.graph.GraphRagQualityReport;
import org.smartledge.ai.knowledge.augmentation.service.GraphRagQualityService;
import org.smartledge.enums.BusinessStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GraphRagQualityServiceImpl implements GraphRagQualityService {

    private static final String LLM_CONTROLLED_EXTRACT = "llm.controlled.extract.v1";

    private final SuperAgentKgEntityMapper entityMapper;

    private final SuperAgentKgRelationMapper relationMapper;

    private final SuperAgentKgEvidenceMapper evidenceMapper;

    private final ObjectMapper objectMapper;

    public GraphRagQualityServiceImpl(SuperAgentKgEntityMapper entityMapper,
                                      SuperAgentKgRelationMapper relationMapper,
                                      SuperAgentKgEvidenceMapper evidenceMapper,
                                      ObjectMapper objectMapper) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public GraphRagQualityReport evaluate(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return GraphRagQualityReport.empty(documentId, taskId);
        }
        List<SuperAgentKgEntity> entities = activeEntities(documentId, taskId);
        List<SuperAgentKgRelation> relations = activeRelations(documentId, taskId);
        List<SuperAgentKgEvidence> evidences = activeEvidences(documentId, taskId);
        long graphItemCount = (long) entities.size() + relations.size();
        if (graphItemCount == 0L && evidences.isEmpty()) {
            return GraphRagQualityReport.empty(documentId, taskId);
        }

        Map<Long, SuperAgentKgRelation> relationById = relations.stream()
            .filter(relation -> relation != null && relation.getId() != null)
            .collect(Collectors.toMap(SuperAgentKgRelation::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        Set<Long> groundedEntityIds = new LinkedHashSet<>();
        Set<Long> groundedRelationIds = new LinkedHashSet<>();
        long traceableEvidenceCount = 0L;
        for (SuperAgentKgEvidence evidence : evidences) {
            if (evidence == null) {
                continue;
            }
            boolean traceable = evidence.getChunkId() != null && StrUtil.isNotBlank(evidence.getQuoteText());
            if (traceable) {
                traceableEvidenceCount++;
            }
            if (evidence.getEntityId() != null && traceable) {
                groundedEntityIds.add(evidence.getEntityId());
            }
            if (evidence.getRelationId() != null && traceable) {
                groundedRelationIds.add(evidence.getRelationId());
                SuperAgentKgRelation relation = relationById.get(evidence.getRelationId());
                if (relation != null) {
                    groundedEntityIds.add(relation.getSourceEntityId());
                    groundedEntityIds.add(relation.getTargetEntityId());
                }
            }
        }
        groundedEntityIds.remove(null);

        long rankedGraphItemCount = entities.stream().filter(this::hasRankBoost).count()
            + relations.stream().filter(this::hasRankBoost).count();
        long controlledExtractionItemCount = entities.stream().filter(this::fromControlledExtraction).count()
            + relations.stream().filter(this::fromControlledExtraction).count()
            + evidences.stream().filter(this::fromControlledExtraction).count();
        long entityResolutionEnhancedCount = entities.stream()
            .filter(entity -> booleanMetadataValue(entity.getMetadataJson(), "entityResolutionEnhanced"))
            .count();

        double entityCoverage = ratio(groundedEntityIds.size(), entities.size());
        double relationCoverage = ratio(groundedRelationIds.size(), relations.size());
        double evidenceCoverage = ratio(traceableEvidenceCount, evidences.size());
        double rankCoverage = ratio(rankedGraphItemCount, graphItemCount);
        double qualityScore = qualityScore(
            graphItemCount,
            scoreRatio(traceableEvidenceCount, evidences.size(), false),
            scoreRatio(groundedEntityIds.size(), entities.size(), true),
            scoreRatio(groundedRelationIds.size(), relations.size(), true),
            scoreRatio(rankedGraphItemCount, graphItemCount, true)
        );
        String level = qualityLevel(qualityScore);

        return GraphRagQualityReport.builder()
            .documentId(documentId)
            .taskId(taskId)
            .qualityLevel(level)
            .qualityScore(qualityScore)
            .summary(summary(level, qualityScore, evidenceCoverage, entityCoverage, relationCoverage))
            .entityCount((long) entities.size())
            .relationCount((long) relations.size())
            .evidenceCount((long) evidences.size())
            .groundedEntityCount((long) groundedEntityIds.size())
            .groundedRelationCount((long) groundedRelationIds.size())
            .traceableEvidenceCount(traceableEvidenceCount)
            .rankedGraphItemCount(rankedGraphItemCount)
            .controlledExtractionItemCount(controlledExtractionItemCount)
            .entityResolutionEnhancedCount(entityResolutionEnhancedCount)
            .entityEvidenceCoverage(entityCoverage)
            .relationEvidenceCoverage(relationCoverage)
            .evidenceTraceabilityCoverage(evidenceCoverage)
            .rankCoverage(rankCoverage)
            .signals(signals(
                entities.size(),
                relations.size(),
                evidences.size(),
                graphItemCount,
                traceableEvidenceCount,
                groundedEntityIds.size(),
                groundedRelationIds.size(),
                rankedGraphItemCount,
                controlledExtractionItemCount,
                entityResolutionEnhancedCount
            ))
            .build();
    }

    private List<SuperAgentKgEntity> activeEntities(Long documentId, Long taskId) {
        return safeList(entityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getDocumentId, documentId)
            .eq(SuperAgentKgEntity::getTaskId, taskId)
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode())));
    }

    private List<SuperAgentKgRelation> activeRelations(Long documentId, Long taskId) {
        return safeList(relationMapper.selectList(new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, documentId)
            .eq(SuperAgentKgRelation::getTaskId, taskId)
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode())));
    }

    private List<SuperAgentKgEvidence> activeEvidences(Long documentId, Long taskId) {
        return safeList(evidenceMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getDocumentId, documentId)
            .eq(SuperAgentKgEvidence::getTaskId, taskId)
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode())));
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean fromControlledExtraction(SuperAgentKgEntity entity) {
        return entity != null && metadataContains(entity.getMetadataJson(), LLM_CONTROLLED_EXTRACT);
    }

    private boolean fromControlledExtraction(SuperAgentKgRelation relation) {
        return relation != null && metadataContains(relation.getMetadataJson(), LLM_CONTROLLED_EXTRACT);
    }

    private boolean fromControlledExtraction(SuperAgentKgEvidence evidence) {
        return evidence != null && metadataContains(evidence.getMetadataJson(), LLM_CONTROLLED_EXTRACT);
    }

    private boolean hasRankBoost(SuperAgentKgEntity entity) {
        return entity != null && numberMetadataValue(entity.getMetadataJson(), "rankBoost") > 0D;
    }

    private boolean hasRankBoost(SuperAgentKgRelation relation) {
        return relation != null && numberMetadataValue(relation.getMetadataJson(), "rankBoost") > 0D;
    }

    private boolean metadataContains(String metadataJson, String expectedValue) {
        if (StrUtil.isBlank(metadataJson) || StrUtil.isBlank(expectedValue)) {
            return false;
        }
        return containsValue(readMap(metadataJson), expectedValue);
    }

    private boolean containsValue(Object value, String expectedValue) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) {
                if (containsValue(child, expectedValue)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                if (containsValue(child, expectedValue)) {
                    return true;
                }
            }
            return false;
        }
        return expectedValue.equals(String.valueOf(value));
    }

    private boolean booleanMetadataValue(String metadataJson, String key) {
        Object value = readMap(metadataJson).get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private double numberMetadataValue(String metadataJson, String key) {
        Object value = readMap(metadataJson).get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0D : Double.parseDouble(String.valueOf(value));
        }
        catch (NumberFormatException exception) {
            return 0D;
        }
    }

    private Map<String, Object> readMap(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        }
        catch (Exception exception) {
            return Map.of();
        }
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0D;
        }
        return round((double) numerator / denominator);
    }

    private double scoreRatio(long numerator, long denominator, boolean emptyAsFull) {
        if (denominator <= 0L) {
            return emptyAsFull ? 1D : 0D;
        }
        return Math.min(1D, Math.max(0D, (double) numerator / denominator));
    }

    private double qualityScore(long graphItemCount,
                                double evidenceCoverage,
                                double entityCoverage,
                                double relationCoverage,
                                double rankCoverage) {
        if (graphItemCount <= 0L) {
            return 0D;
        }
        return round(evidenceCoverage * 0.39D
            + entityCoverage * 0.28D
            + relationCoverage * 0.28D
            + rankCoverage * 0.05D);
    }

    private String qualityLevel(double qualityScore) {
        if (qualityScore >= 0.85D) {
            return GraphRagQualityReport.LEVEL_STRONG;
        }
        if (qualityScore >= 0.65D) {
            return GraphRagQualityReport.LEVEL_WATCH;
        }
        return GraphRagQualityReport.LEVEL_WEAK;
    }

    private String summary(String level,
                           double qualityScore,
                           double evidenceCoverage,
                           double entityCoverage,
                           double relationCoverage) {
        String levelText = switch (level) {
            case GraphRagQualityReport.LEVEL_STRONG -> "较强";
            case GraphRagQualityReport.LEVEL_WATCH -> "需观察";
            default -> "偏弱";
        };
        return "GraphRAG 质量" + levelText
            + "，综合分 " + percent(qualityScore)
            + "，证据追溯 " + percent(evidenceCoverage)
            + "，实体证据覆盖 " + percent(entityCoverage)
            + "，关系证据覆盖 " + percent(relationCoverage) + "。";
    }

    private List<GraphRagQualityReport.SignalItem> signals(int entityCount,
                                                           int relationCount,
                                                           int evidenceCount,
                                                           long graphItemCount,
                                                           long traceableEvidenceCount,
                                                           int groundedEntityCount,
                                                           int groundedRelationCount,
                                                           long rankedGraphItemCount,
                                                           long controlledExtractionItemCount,
                                                           long entityResolutionEnhancedCount) {
        List<GraphRagQualityReport.SignalItem> result = new ArrayList<>();
        result.add(signal("证据追溯", traceableEvidenceCount, evidenceCount, "evidence 需要同时有 chunkId 和 quoteText。"));
        result.add(signal("实体证据覆盖", groundedEntityCount, entityCount, "实体需要通过 entity evidence 或 relation evidence 回到原文。"));
        result.add(signal("关系证据覆盖", groundedRelationCount, relationCount, "关系需要有可追溯的 relation evidence。"));
        result.add(signal("图谱 Rank 覆盖", rankedGraphItemCount, graphItemCount, "实体、关系 metadata 应有 rankBoost。"));
        long controlledTotal = controlledExtractionItemCount + entityResolutionEnhancedCount;
        result.add(GraphRagQualityReport.SignalItem.builder()
            .label("受控增强命中")
            .value(String.valueOf(controlledTotal))
            .hint("extraction、entity resolution 通过 Java 校验后的增强命中数。")
            .tone(controlledTotal > 0L ? "success" : "neutral")
            .build());
        return result;
    }

    private GraphRagQualityReport.SignalItem signal(String label, long numerator, long denominator, String hint) {
        String value = denominator <= 0L ? "无数据" : percent(ratio(numerator, denominator));
        return GraphRagQualityReport.SignalItem.builder()
            .label(label)
            .value(value)
            .hint(hint)
            .tone(denominator <= 0L ? "neutral" : tone(ratio(numerator, denominator)))
            .build();
    }

    private String tone(double ratio) {
        if (ratio >= 0.85D) {
            return "success";
        }
        if (ratio >= 0.65D) {
            return "warning";
        }
        return "danger";
    }

    private String percent(double value) {
        return Math.round(Math.max(0D, Math.min(1D, value)) * 100D) + "%";
    }

    private double round(double value) {
        return Math.round(Math.max(0D, Math.min(1D, value)) * 10000D) / 10000D;
    }
}
