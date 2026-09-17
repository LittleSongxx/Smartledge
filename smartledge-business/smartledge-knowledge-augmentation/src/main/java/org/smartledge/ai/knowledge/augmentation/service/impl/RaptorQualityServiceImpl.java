package org.smartledge.ai.knowledge.augmentation.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentRaptorNode;
import org.smartledge.ai.knowledge.augmentation.mapper.SuperAgentRaptorNodeMapper;
import org.smartledge.ai.knowledge.augmentation.model.raptor.RaptorQualityReport;
import org.smartledge.ai.knowledge.augmentation.service.RaptorQualityService;
import org.smartledge.enums.BusinessStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class RaptorQualityServiceImpl implements RaptorQualityService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final double LOW_QUALITY_LINE = 0.55D;

    private static final double WATCH_QUALITY_LINE = 0.68D;

    private static final double HIGH_QUALITY_LINE = 0.82D;

    private final SuperAgentRaptorNodeMapper raptorNodeMapper;

    private final ChatRagProperties chatRagProperties;

    private final ObjectMapper objectMapper;

    @Override
    public RaptorQualityReport evaluate(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return emptyReport();
        }
        List<SuperAgentRaptorNode> nodes = raptorNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getDocumentId, documentId)
            .eq(SuperAgentRaptorNode::getTaskId, taskId)
            .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentRaptorNode::getNodeLevel, SuperAgentRaptorNode::getNodeNo, SuperAgentRaptorNode::getId));
        return evaluate(nodes, qualityFloor());
    }

    public RaptorQualityReport evaluate(List<SuperAgentRaptorNode> nodes, double configuredFloor) {
        if (CollUtil.isEmpty(nodes)) {
            return emptyReport(configuredFloor);
        }
        List<NodeQuality> qualities = nodes.stream()
            .map(this::toNodeQuality)
            .toList();
        return buildReport(qualities, configuredFloor);
    }

    @Override
    public RaptorQualityReport evaluatePythonNodes(List<org.smartledge.ai.knowledge.augmentation.model.RaptorBuildResponse.Node> nodes,
                                                   double configuredFloor) {
        if (CollUtil.isEmpty(nodes)) {
            return emptyReport(configuredFloor);
        }
        List<NodeQuality> qualities = nodes.stream()
            .filter(Objects::nonNull)
            .map(node -> {
                Map<String, Object> metadata = objectMap(node.getMetadata());
                Map<String, Object> clusterSignals = objectMap(metadata.get("clusterQualitySignals"));
                return new NodeQuality(
                    node.getLevel(),
                    normalized(node.getQualityScore()),
                    abstractiveFromMetadata(node.getMetadata()),
                    stringValue(node.getId()),
                    doubleValue(clusterSignals.get("avgClusterSize")),
                    integerValue(clusterSignals.get("maxClusterSizeObserved")),
                    integerValue(clusterSignals.get("singletonClusterCount")),
                    doubleValue(clusterSignals.get("levelCompressionRatio")),
                    doubleValue(clusterSignals.get("avgIntraClusterSimilarity")),
                    doubleValue(clusterSignals.get("treeBalanceScore"))
                );
            })
            .toList();
        return buildReport(qualities, configuredFloor);
    }

    private RaptorQualityReport buildReport(List<NodeQuality> qualities, double configuredFloor) {
        if (qualities.isEmpty()) {
            return emptyReport(configuredFloor);
        }
        List<Double> scores = qualities.stream()
            .map(NodeQuality::qualityScore)
            .sorted()
            .toList();
        long nodeCount = qualities.size();
        long abstractiveCount = qualities.stream().filter(NodeQuality::abstractive).count();
        long lowCount = scores.stream().filter(score -> score < LOW_QUALITY_LINE).count();
        long watchCount = scores.stream().filter(score -> score >= LOW_QUALITY_LINE && score < WATCH_QUALITY_LINE).count();
        long highCount = scores.stream().filter(score -> score >= HIGH_QUALITY_LINE).count();
        long blockedCount = scores.stream().filter(score -> score < configuredFloor).count();
        ClusterQuality clusterQuality = clusterQuality(qualities);

        double average = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        double min = scores.get(0);
        double p10 = percentile(scores, 0.10D);
        double median = percentile(scores, 0.50D);
        double p90 = percentile(scores, 0.90D);
        double recommendedFloor = recommendFloor(p10, median, average, configuredFloor);

        String level = qualityLevel(average, median, lowCount, nodeCount, abstractiveCount);
        return RaptorQualityReport.builder()
            .qualityLevel(level)
            .configuredQualityFloor(round(configuredFloor))
            .recommendedQualityFloor(round(recommendedFloor))
            .averageQualityScore(round(average))
            .minQualityScore(round(min))
            .p10QualityScore(round(p10))
            .medianQualityScore(round(median))
            .p90QualityScore(round(p90))
            .nodeCount(nodeCount)
            .abstractiveNodeCount(abstractiveCount)
            .abstractiveCoverage(round(ratio(abstractiveCount, nodeCount)))
            .lowQualityNodeCount(lowCount)
            .lowQualityRatio(round(ratio(lowCount, nodeCount)))
            .watchNodeCount(watchCount)
            .watchRatio(round(ratio(watchCount, nodeCount)))
            .highQualityNodeCount(highCount)
            .floorBlockedNodeCount(blockedCount)
            .floorBlockedRatio(round(ratio(blockedCount, nodeCount)))
            .averageClusterSize(roundMetric(clusterQuality.averageClusterSize()))
            .maxClusterSizeObserved(clusterQuality.maxClusterSizeObserved())
            .singletonClusterCount(clusterQuality.singletonClusterCount())
            .averageLevelCompressionRatio(round(clusterQuality.averageLevelCompressionRatio()))
            .averageIntraClusterSimilarity(round(clusterQuality.averageIntraClusterSimilarity()))
            .averageTreeBalanceScore(round(clusterQuality.averageTreeBalanceScore()))
            .summary(summary(level, nodeCount, average, median, configuredFloor, recommendedFloor, blockedCount))
            .tuningSuggestions(suggestions(level, configuredFloor, recommendedFloor, lowCount, watchCount, blockedCount, nodeCount, abstractiveCount))
            .levelBuckets(levelBuckets(qualities))
            .build();
    }

    private NodeQuality toNodeQuality(SuperAgentRaptorNode node) {
        Map<String, Object> metadata = readMap(node == null ? null : node.getMetadataJson());
        Map<String, Object> sourceMetadata = objectMap(metadata.get("sourceMetadata"));
        Map<String, Object> signals = objectMap(sourceMetadata.get("summaryQualitySignals"));
        Map<String, Object> clusterSignals = objectMap(sourceMetadata.get("clusterQualitySignals"));
        return new NodeQuality(
            node == null ? null : node.getNodeLevel(),
            normalized(firstPresent(metadata.get("summaryQualityScore"), nodeQualityFromSignals(signals))),
            booleanValue(firstPresent(signals.get("abstractive"), sourceMetadata.get("abstractive"))),
            node == null ? "" : StrUtil.blankToDefault(node.getNodeKey(), String.valueOf(node.getId())),
            doubleValue(clusterSignals.get("avgClusterSize")),
            integerValue(clusterSignals.get("maxClusterSizeObserved")),
            integerValue(clusterSignals.get("singletonClusterCount")),
            doubleValue(clusterSignals.get("levelCompressionRatio")),
            doubleValue(clusterSignals.get("avgIntraClusterSimilarity")),
            doubleValue(clusterSignals.get("treeBalanceScore"))
        );
    }

    private ClusterQuality clusterQuality(List<NodeQuality> qualities) {
        Map<Integer, NodeQuality> signalByLevel = qualities.stream()
            .filter(NodeQuality::hasClusterSignals)
            .collect(Collectors.toMap(
                quality -> quality.level() == null ? 0 : quality.level(),
                quality -> quality,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        List<NodeQuality> signalItems = new ArrayList<>(signalByLevel.values());
        if (signalItems.isEmpty()) {
            return new ClusterQuality(0D, 0, 0L, 0D, 0D, 0D);
        }
        return new ClusterQuality(
            signalItems.stream().map(NodeQuality::avgClusterSize).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0D),
            signalItems.stream().map(NodeQuality::maxClusterSizeObserved).filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0),
            signalItems.stream().map(NodeQuality::singletonClusterCount).filter(Objects::nonNull).mapToLong(Integer::longValue).sum(),
            signalItems.stream().map(NodeQuality::levelCompressionRatio).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0D),
            signalItems.stream().map(NodeQuality::avgIntraClusterSimilarity).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0D),
            signalItems.stream().map(NodeQuality::treeBalanceScore).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0D)
        );
    }

    private List<RaptorQualityReport.LevelBucket> levelBuckets(List<NodeQuality> qualities) {
        return qualities.stream()
            .collect(Collectors.groupingBy(
                quality -> quality.level() == null ? 0 : quality.level(),
                LinkedHashMap::new,
                Collectors.toList()
            ))
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> {
                List<Double> scores = entry.getValue().stream().map(NodeQuality::qualityScore).toList();
                return RaptorQualityReport.LevelBucket.builder()
                    .level(entry.getKey())
                    .nodeCount((long) scores.size())
                    .averageQualityScore(round(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0D)))
                    .minQualityScore(round(scores.stream().mapToDouble(Double::doubleValue).min().orElse(0D)))
                    .maxQualityScore(round(scores.stream().mapToDouble(Double::doubleValue).max().orElse(0D)))
                    .build();
            })
            .toList();
    }

    private double recommendFloor(double p10, double median, double average, double configuredFloor) {
        double base;
        if (median >= 0.76D && average >= 0.72D) {
            base = Math.max(0.52D, Math.min(0.62D, p10 - 0.03D));
        }
        else if (median >= 0.68D) {
            base = Math.max(0.45D, Math.min(0.56D, p10 - 0.02D));
        }
        else {
            base = Math.max(0.42D, Math.min(0.50D, p10));
        }
        if (base < configuredFloor && configuredFloor - base <= 0.05D) {
            return configuredFloor;
        }
        return Math.max(0D, Math.min(0.75D, base));
    }

    private String qualityLevel(double average, double median, long lowCount, long nodeCount, long abstractiveCount) {
        double lowRatio = ratio(lowCount, nodeCount);
        double abstractiveRatio = ratio(abstractiveCount, nodeCount);
        if (average >= 0.72D && median >= 0.72D && lowRatio <= 0.12D && abstractiveRatio >= 0.60D) {
            return RaptorQualityReport.LEVEL_STRONG;
        }
        if (average >= 0.62D && median >= 0.62D && lowRatio <= 0.30D) {
            return RaptorQualityReport.LEVEL_WATCH;
        }
        return RaptorQualityReport.LEVEL_WEAK;
    }

    private String summary(String level,
                           long nodeCount,
                           double average,
                           double median,
                           double configuredFloor,
                           double recommendedFloor,
                           long blockedCount) {
        if (RaptorQualityReport.LEVEL_EMPTY.equals(level)) {
            return "当前没有可评测的 RAPTOR 摘要节点。";
        }
        String levelText = switch (level) {
            case RaptorQualityReport.LEVEL_STRONG -> "整体质量稳定";
            case RaptorQualityReport.LEVEL_WATCH -> "整体可用但需要观察";
            default -> "质量偏弱，需要优先复查摘要和聚类";
        };
        return levelText + "：已评测 " + nodeCount + " 个摘要节点，平均 "
            + percentText(average) + "，中位数 " + percentText(median)
            + "，当前入库阈值 " + percentText(configuredFloor)
            + "，建议下一轮试探阈值 " + percentText(recommendedFloor)
            + "，当前样本中低于阈值 " + blockedCount + " 个。";
    }

    private List<String> suggestions(String level,
                                     double configuredFloor,
                                     double recommendedFloor,
                                     long lowCount,
                                     long watchCount,
                                     long blockedCount,
                                     long nodeCount,
                                     long abstractiveCount) {
        List<String> result = new ArrayList<>();
        if (recommendedFloor > configuredFloor + 0.03D) {
            result.add("当前摘要分布较稳，可以把 SUPER_AGENT_RAPTOR_SUMMARY_QUALITY_FLOOR 从 "
                + percentText(configuredFloor) + " 小步上调到 " + percentText(recommendedFloor) + " 后重新构建验证。");
        }
        else if (configuredFloor > recommendedFloor + 0.06D) {
            result.add("当前阈值可能偏紧，建议先保持或小幅下调到 " + percentText(recommendedFloor)
                + "，避免过度过滤长文档全局摘要。");
        }
        else {
            result.add("当前阈值与样本分布基本匹配，先保持 " + percentText(configuredFloor)
                + "，用真实问答命中和 citation 结果继续观察。");
        }
        if (lowCount > 0) {
            result.add("存在 " + lowCount + " 个低分摘要节点，优先检查这些节点的 source chunk 覆盖、summaryWithWeight 和原文下钻证据。");
        }
        if (watchCount > 0 && ratio(watchCount, nodeCount) >= 0.25D) {
            result.add("观察区间节点占比较高，后续应优先调聚类大小、树层数和摘要 prompt，而不是为业务词写特化规则。");
        }
        if (blockedCount == 0 && !RaptorQualityReport.LEVEL_WEAK.equals(level)) {
            result.add("当前已入库节点都高于阈值；下一轮调参时重点看构建日志中的 Python 原始节点分布，确认是否有被过滤节点。");
        }
        if (ratio(abstractiveCount, nodeCount) < 0.60D) {
            result.add("LLM 抽象摘要覆盖率不足，先检查 rag-tools LLM baseUrl/apiKey/model 和 Python 日志，再谈阈值上调。");
        }
        return result;
    }

    private RaptorQualityReport emptyReport() {
        return emptyReport(qualityFloor());
    }

    private RaptorQualityReport emptyReport(double configuredFloor) {
        return RaptorQualityReport.builder()
            .qualityLevel(RaptorQualityReport.LEVEL_EMPTY)
            .configuredQualityFloor(round(configuredFloor))
            .recommendedQualityFloor(round(configuredFloor))
            .averageQualityScore(0D)
            .minQualityScore(0D)
            .p10QualityScore(0D)
            .medianQualityScore(0D)
            .p90QualityScore(0D)
            .nodeCount(0L)
            .abstractiveNodeCount(0L)
            .abstractiveCoverage(0D)
            .lowQualityNodeCount(0L)
            .lowQualityRatio(0D)
            .watchNodeCount(0L)
            .watchRatio(0D)
            .highQualityNodeCount(0L)
            .floorBlockedNodeCount(0L)
            .floorBlockedRatio(0D)
            .averageClusterSize(0D)
            .maxClusterSizeObserved(0)
            .singletonClusterCount(0L)
            .averageLevelCompressionRatio(0D)
            .averageIntraClusterSimilarity(0D)
            .averageTreeBalanceScore(0D)
            .summary("当前没有可评测的 RAPTOR 摘要节点。")
            .tuningSuggestions(List.of("先完成索引构建并生成 RAPTOR 节点，再进行摘要质量评测和阈值调优。"))
            .levelBuckets(List.of())
            .build();
    }

    private double percentile(List<Double> sortedScores, double percentile) {
        if (sortedScores.isEmpty()) {
            return 0D;
        }
        if (sortedScores.size() == 1) {
            return sortedScores.get(0);
        }
        double position = percentile * (sortedScores.size() - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        if (lowerIndex == upperIndex) {
            return sortedScores.get(lowerIndex);
        }
        double weight = position - lowerIndex;
        return sortedScores.get(lowerIndex) * (1D - weight) + sortedScores.get(upperIndex) * weight;
    }

    private double qualityFloor() {
        return Math.max(0D, Math.min(1D, chatRagProperties.getRaptorSummaryQualityFloor()));
    }

    private double ratio(long numerator, long denominator) {
        return denominator <= 0L ? 0D : (double) numerator / denominator;
    }

    private double normalized(Object value) {
        Double score = doubleValue(value);
        return score == null ? 0D : Math.max(0D, Math.min(1D, score));
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return Double.parseDouble(text);
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return Integer.parseInt(text);
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double nodeQualityFromSignals(Map<String, Object> signals) {
        return doubleValue(firstPresent(signals.get("summaryQualityScore"), signals.get("qualityScore")));
    }

    private boolean abstractiveFromMetadata(Map<String, Object> metadata) {
        Map<String, Object> sourceMetadata = objectMap(metadata);
        Map<String, Object> signals = objectMap(sourceMetadata.get("summaryQualitySignals"));
        return booleanValue(firstPresent(signals.get("abstractive"), sourceMetadata.get("abstractive")));
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    private Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value instanceof String text && StrUtil.isBlank(text)) {
                continue;
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String percentText(double value) {
        return Math.round(Math.max(0D, Math.min(1D, value)) * 100D) + "%";
    }

    private double round(double value) {
        return Math.round(Math.max(0D, Math.min(1D, value)) * 10000D) / 10000D;
    }

    private double roundMetric(double value) {
        return Math.round(Math.max(0D, value) * 10000D) / 10000D;
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

    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return Map.of();
    }

    private record NodeQuality(Integer level,
                               double qualityScore,
                               boolean abstractive,
                               String nodeKey,
                               Double avgClusterSize,
                               Integer maxClusterSizeObserved,
                               Integer singletonClusterCount,
                               Double levelCompressionRatio,
                               Double avgIntraClusterSimilarity,
                               Double treeBalanceScore) {

        private boolean hasClusterSignals() {
            return avgClusterSize != null
                || maxClusterSizeObserved != null
                || singletonClusterCount != null
                || levelCompressionRatio != null
                || avgIntraClusterSimilarity != null
                || treeBalanceScore != null;
        }
    }

    private record ClusterQuality(double averageClusterSize,
                                  int maxClusterSizeObserved,
                                  long singletonClusterCount,
                                  double averageLevelCompressionRatio,
                                  double averageIntraClusterSimilarity,
                                  double averageTreeBalanceScore) {
    }
}
