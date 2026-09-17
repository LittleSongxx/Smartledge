package org.smartledge.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.manage.vo.QualityOverviewVo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: 聚合装配
 * @author: Song
 **/
public final class QualityOverviewAssembler {

    private static final DateTimeFormatter DATE_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("MM-dd");

    private static final String MODE_AUTO = "auto";

    private static final String MODE_SHADOW = "shadow";

    /**
     * 检索通道展示名。项目内 GraphRAG 与 RAPTOR 已统一改用中文业务称呼，此处保持一致。
     */
    private static final Map<String, String> CHANNEL_NAMES = Map.of(
        "vector", "向量检索",
        "keyword", "关键词检索",
        "raptor", "层级结构树",
        "graph-rag", "知识图谱",
        "table", "表格检索",
        "hybrid", "混合检索"
    );

    private QualityOverviewAssembler() {
    }

    /**
     * 路由某天某模式的原始聚合行。
     */
    public record RouteDailyAggregate(LocalDate date, String mode, long totalCount, long successCount,
                                      long lowConfidenceCount, long failedCount, double averageConfidence) {
    }

    /**
     * 通道执行计数器聚合行。
     */
    public record ChannelExecutionAggregate(String channelType, long executionCount, long recalledCount,
                                           long acceptedCount, long selectedCount) {
    }

    /**
     * 检索快照聚合行。
     */
    public record RetrievalSnapshotAggregate(String channelType, long snapshotCount,
                                            long selectedSnapshotCount, long resolvedCount) {
    }

    /**
     * 文档任务某天某类型的聚合行。
     */
    public record DocumentTaskAggregate(LocalDate date, int taskType, long taskCount, double averageCostMs) {
    }

    /**
     * 路由窗口整体计数，用于仪表与环比。
     */
    public record RouteTotals(long totalCount, long successCount, long lowConfidenceCount,
                              long failedCount, double averageConfidence) {
    }

    /**
     * 检索快照窗口整体计数，用于命中率环比。
     */
    public record RetrievalTotals(long snapshotCount, long selectedSnapshotCount) {
    }

    /**
     * 把按天 + mode 的行合并成每天一个双序列点。
     * 某天缺失某个 mode 时该 mode 的字段留空串，前端据此断线，不补零。
     */
    public static List<QualityOverviewVo.RouteTrendPoint> buildRouteTrend(List<RouteDailyAggregate> aggregates) {
        Map<LocalDate, List<RouteDailyAggregate>> byDate = new LinkedHashMap<>();
        for (RouteDailyAggregate aggregate : safeList(aggregates)) {
            if (aggregate == null || aggregate.date() == null) {
                continue;
            }
            byDate.computeIfAbsent(aggregate.date(), key -> new ArrayList<>()).add(aggregate);
        }

        List<LocalDate> orderedDates = new ArrayList<>(byDate.keySet());
        orderedDates.sort(Comparator.naturalOrder());

        List<QualityOverviewVo.RouteTrendPoint> points = new ArrayList<>();
        for (LocalDate date : orderedDates) {
            RouteDailyAggregate autoRow = pickMode(byDate.get(date), MODE_AUTO);
            RouteDailyAggregate shadowRow = pickMode(byDate.get(date), MODE_SHADOW);
            points.add(new QualityOverviewVo.RouteTrendPoint(
                date.format(DATE_KEY),
                date.format(DATE_LABEL),
                countText(autoRow),
                successRateText(autoRow),
                confidenceText(autoRow),
                countText(shadowRow),
                successRateText(shadowRow),
                confidenceText(shadowRow)
            ));
        }
        return points;
    }

    /**
     * 通道剖面：召回与选入来自执行计数器，证据可解析率来自快照表。
     * 两张表都缺该通道时不产出该轴。
     */
    public static List<QualityOverviewVo.ChannelProfileItem> buildChannelProfiles(
        List<ChannelExecutionAggregate> executions,
        List<RetrievalSnapshotAggregate> snapshots) {

        Map<String, RetrievalSnapshotAggregate> snapshotByChannel = new LinkedHashMap<>();
        for (RetrievalSnapshotAggregate snapshot : safeList(snapshots)) {
            if (snapshot == null || StrUtil.isBlank(snapshot.channelType())) {
                continue;
            }
            snapshotByChannel.put(snapshot.channelType().trim(), snapshot);
        }

        List<QualityOverviewVo.ChannelProfileItem> items = new ArrayList<>();
        for (ChannelExecutionAggregate execution : safeList(executions)) {
            if (execution == null || StrUtil.isBlank(execution.channelType())) {
                continue;
            }
            String channelType = execution.channelType().trim();
            RetrievalSnapshotAggregate snapshot = snapshotByChannel.get(channelType);
            long snapshotCount = snapshot == null ? 0L : snapshot.snapshotCount();
            long resolvedCount = snapshot == null ? 0L : snapshot.resolvedCount();
            items.add(new QualityOverviewVo.ChannelProfileItem(
                channelType,
                resolveChannelName(channelType),
                String.valueOf(execution.recalledCount()),
                String.valueOf(execution.selectedCount()),
                formatRate(execution.selectedCount(), execution.recalledCount()),
                formatRate(resolvedCount, snapshotCount),
                String.valueOf(snapshotCount),
                String.valueOf(execution.executionCount())
            ));
        }
        return items;
    }

    public static QualityOverviewVo.RouteDecision buildRouteDecision(RouteTotals totals) {
        RouteTotals safe = totals == null ? new RouteTotals(0, 0, 0, 0, 0D) : totals;
        return new QualityOverviewVo.RouteDecision(
            String.valueOf(safe.totalCount()),
            String.valueOf(safe.successCount()),
            String.valueOf(safe.lowConfidenceCount()),
            String.valueOf(safe.failedCount()),
            formatRate(safe.successCount(), safe.totalCount())
        );
    }

    /**
     * 文档处理耗时。只统计成功任务，解析与建索引各自独立求均值。
     */
    public static QualityOverviewVo.DocProcessing buildDocProcessing(List<DocumentTaskAggregate> aggregates) {
        Map<LocalDate, List<DocumentTaskAggregate>> byDate = new LinkedHashMap<>();
        long parseCount = 0L;
        long indexCount = 0L;
        double parseCostSum = 0D;
        double indexCostSum = 0D;

        for (DocumentTaskAggregate aggregate : safeList(aggregates)) {
            if (aggregate == null || aggregate.date() == null) {
                continue;
            }
            byDate.computeIfAbsent(aggregate.date(), key -> new ArrayList<>()).add(aggregate);
            if (aggregate.taskType() == 1) {
                parseCount += aggregate.taskCount();
                parseCostSum += aggregate.averageCostMs() * aggregate.taskCount();
            }
            else if (aggregate.taskType() == 2) {
                indexCount += aggregate.taskCount();
                indexCostSum += aggregate.averageCostMs() * aggregate.taskCount();
            }
        }

        List<LocalDate> orderedDates = new ArrayList<>(byDate.keySet());
        orderedDates.sort(Comparator.naturalOrder());

        List<QualityOverviewVo.DocProcessingItem> items = new ArrayList<>();
        for (LocalDate date : orderedDates) {
            DocumentTaskAggregate parseRow = pickTaskType(byDate.get(date), 1);
            DocumentTaskAggregate indexRow = pickTaskType(byDate.get(date), 2);
            items.add(new QualityOverviewVo.DocProcessingItem(
                date.format(DATE_KEY),
                date.format(DATE_LABEL),
                parseRow == null ? "0" : String.valueOf(parseRow.taskCount()),
                parseRow == null ? "0" : String.valueOf(Math.round(parseRow.averageCostMs())),
                indexRow == null ? "0" : String.valueOf(indexRow.taskCount()),
                indexRow == null ? "0" : String.valueOf(Math.round(indexRow.averageCostMs()))
            ));
        }

        long averageParseMs = parseCount > 0 ? Math.round(parseCostSum / parseCount) : 0L;
        long averageIndexMs = indexCount > 0 ? Math.round(indexCostSum / indexCount) : 0L;
        String ratio = averageParseMs > 0
            ? BigDecimal.valueOf((double) averageIndexMs / averageParseMs)
                .setScale(1, RoundingMode.HALF_UP).toPlainString()
            : "";

        return new QualityOverviewVo.DocProcessing(
            String.valueOf(parseCount),
            String.valueOf(indexCount),
            String.valueOf(averageParseMs),
            String.valueOf(averageIndexMs),
            ratio,
            items
        );
    }

    /**
     * 构造一个 KPI 指标。previous 为 null 表示不可比（全部时间窗），此时 delta 与 previousValue 都为空串。
     */
    public static QualityOverviewVo.KpiMetric buildMetric(String key, String label, String valueType,
                                                          String currentValue, String previousValue,
                                                          boolean higherIsBetter, String hint,
                                                          List<QualityOverviewVo.SparkPoint> sparkline) {
        String delta = resolveDelta(currentValue, previousValue);
        return new QualityOverviewVo.KpiMetric(
            key,
            label,
            currentValue == null ? "" : currentValue,
            valueType,
            previousValue == null ? "" : previousValue,
            delta,
            higherIsBetter ? "1" : "0",
            sparkline == null ? List.of() : sparkline,
            hint == null ? "" : hint
        );
    }

    /**
     * 某天合并两个模式后的汇总。sparkline 的口径必须和顶部 KPI 一致，
     * 所以这里按次数加权合并 auto 与 shadow，不能只取其中一个模式。
     */
    public record DailyRollup(LocalDate date, long totalCount, long successCount, double averageConfidence) {
    }

    /**
     * 把按天 + mode 的行合并成每天一行，置信度按该模式的路由次数加权。
     */
    public static List<DailyRollup> buildDailyRollups(List<RouteDailyAggregate> aggregates) {
        Map<LocalDate, List<RouteDailyAggregate>> byDate = new LinkedHashMap<>();
        for (RouteDailyAggregate aggregate : safeList(aggregates)) {
            if (aggregate == null || aggregate.date() == null) {
                continue;
            }
            byDate.computeIfAbsent(aggregate.date(), key -> new ArrayList<>()).add(aggregate);
        }

        List<LocalDate> orderedDates = new ArrayList<>(byDate.keySet());
        orderedDates.sort(Comparator.naturalOrder());

        List<DailyRollup> rollups = new ArrayList<>();
        for (LocalDate date : orderedDates) {
            long totalCount = 0L;
            long successCount = 0L;
            double confidenceSum = 0D;
            for (RouteDailyAggregate row : byDate.get(date)) {
                if (row == null) {
                    continue;
                }
                totalCount += row.totalCount();
                successCount += row.successCount();
                confidenceSum += row.averageConfidence() * row.totalCount();
            }
            double averageConfidence = totalCount > 0 ? confidenceSum / totalCount : 0D;
            rollups.add(new DailyRollup(date, totalCount, successCount, averageConfidence));
        }
        return rollups;
    }

    /**
     * 每天的路由总量序列。
     */
    public static List<QualityOverviewVo.SparkPoint> buildTotalCountSparkline(List<DailyRollup> rollups) {
        return mapRollups(rollups, rollup -> String.valueOf(rollup.totalCount()));
    }

    /**
     * 每天的成功率序列，与顶部成功率 KPI 同口径。
     */
    public static List<QualityOverviewVo.SparkPoint> buildSuccessRateSparkline(List<DailyRollup> rollups) {
        return mapRollups(rollups, rollup -> formatRate(rollup.successCount(), rollup.totalCount()));
    }

    /**
     * 每天的加权平均置信度序列，与顶部置信度 KPI 同口径。
     */
    public static List<QualityOverviewVo.SparkPoint> buildConfidenceSparkline(List<DailyRollup> rollups) {
        return mapRollups(rollups, rollup -> formatConfidence(rollup.averageConfidence()));
    }

    private static List<QualityOverviewVo.SparkPoint> mapRollups(
        List<DailyRollup> rollups,
        java.util.function.Function<DailyRollup, String> extractor) {

        List<QualityOverviewVo.SparkPoint> points = new ArrayList<>();
        for (DailyRollup rollup : safeList(rollups)) {
            if (rollup == null || rollup.date() == null) {
                continue;
            }
            points.add(new QualityOverviewVo.SparkPoint(
                rollup.date().format(DATE_KEY),
                rollup.totalCount() > 0 ? extractor.apply(rollup) : ""
            ));
        }
        return points;
    }

    /**
     * 百分比，保留一位小数。分母为 0 时返回 "0.0" 而不是抛错。
     */
    public static String formatRate(long numerator, long denominator) {
        if (denominator <= 0) {
            return "0.0";
        }
        return BigDecimal.valueOf(numerator)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP)
            .toPlainString();
    }

    /**
     * 置信度保留三位小数，与路由追踪页的展示口径一致。
     */
    public static String formatConfidence(double value) {
        return BigDecimal.valueOf(Math.max(0D, value))
            .setScale(3, RoundingMode.HALF_UP)
            .toPlainString();
    }

    public static String resolveWindowLabel(int windowDays) {
        return windowDays <= 0 ? "全部时间" : "近 " + windowDays + " 天";
    }

    public static String resolveChannelName(String channelType) {
        String normalized = channelType == null ? "" : channelType.trim();
        return CHANNEL_NAMES.getOrDefault(normalized, normalized.isEmpty() ? "未知通道" : normalized);
    }

    private static String resolveDelta(String currentValue, String previousValue) {
        if (StrUtil.isBlank(currentValue) || previousValue == null || StrUtil.isBlank(previousValue)) {
            return "";
        }
        try {
            BigDecimal current = new BigDecimal(currentValue.trim());
            BigDecimal previous = new BigDecimal(previousValue.trim());
            return current.subtract(previous).setScale(
                Math.max(current.scale(), previous.scale()), RoundingMode.HALF_UP).toPlainString();
        }
        catch (NumberFormatException exception) {
            return "";
        }
    }

    private static RouteDailyAggregate pickMode(List<RouteDailyAggregate> rows, String mode) {
        for (RouteDailyAggregate row : safeList(rows)) {
            if (row != null && mode.equalsIgnoreCase(StrUtil.trimToEmpty(row.mode()))) {
                return row;
            }
        }
        return null;
    }

    private static DocumentTaskAggregate pickTaskType(List<DocumentTaskAggregate> rows, int taskType) {
        for (DocumentTaskAggregate row : safeList(rows)) {
            if (row != null && row.taskType() == taskType) {
                return row;
            }
        }
        return null;
    }

    private static String countText(RouteDailyAggregate row) {
        return row == null ? "" : String.valueOf(row.totalCount());
    }

    private static String successRateText(RouteDailyAggregate row) {
        return row == null ? "" : formatRate(row.successCount(), row.totalCount());
    }

    private static String confidenceText(RouteDailyAggregate row) {
        return row == null ? "" : formatConfidence(row.averageConfidence());
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}
