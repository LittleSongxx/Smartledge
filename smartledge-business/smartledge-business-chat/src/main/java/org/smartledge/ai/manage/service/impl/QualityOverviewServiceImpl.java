package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import org.smartledge.ai.manage.dto.QualityOverviewQueryDto;
import org.smartledge.ai.manage.mapper.QualityOverviewMapper;
import org.smartledge.ai.manage.service.QualityOverviewService;
import org.smartledge.ai.manage.support.QualityOverviewAssembler;
import org.smartledge.ai.manage.vo.QualityOverviewVo;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @description: 服务实现层
 * @author: Song
 **/
@AllArgsConstructor
@Service
public class QualityOverviewServiceImpl implements QualityOverviewService {

    /**
     * 日粒度序列硬上限。全部时间窗下防止无界 DOM 与无界序列，超过时保留最近的桶并标记截断。
     */
    private static final int DAILY_BUCKET_LIMIT = 60;

    private static final int DEFAULT_WINDOW_DAYS = 30;

    /**
     * 窗口天数上限。windowDays 来自请求体且会被乘 2 用于环比，
     * 不设上限时超大入参会整型溢出成负数，把环比窗口静默变成"窗口起点之前的全部时间"。
     * 10 年足够覆盖 UI 的 7/30/90 档。
     */
    private static final int MAX_WINDOW_DAYS = 3650;

    private final QualityOverviewMapper qualityOverviewMapper;

    @Override
    public QualityOverviewVo queryQualityOverview(QualityOverviewQueryDto dto) {
        int windowDays = resolveWindowDays(dto == null ? null : dto.getWindowDays());
        Date windowStart = resolveWindowStart(windowDays);
        boolean comparable = windowDays > 0;

        List<QualityOverviewAssembler.RouteDailyAggregate> routeDaily =
            toRouteDailyAggregates(safeRows(qualityOverviewMapper.selectRouteDailyAggregates(windowStart)));
        List<QualityOverviewVo.RouteTrendPoint> fullTrend =
            QualityOverviewAssembler.buildRouteTrend(routeDaily);
        List<QualityOverviewAssembler.DailyRollup> fullRollups =
            QualityOverviewAssembler.buildDailyRollups(routeDaily);

        boolean truncated = fullTrend.size() > DAILY_BUCKET_LIMIT;
        List<QualityOverviewVo.RouteTrendPoint> routeTrend = truncated
            ? new ArrayList<>(fullTrend.subList(fullTrend.size() - DAILY_BUCKET_LIMIT, fullTrend.size()))
            : fullTrend;
        List<QualityOverviewAssembler.DailyRollup> rollups = truncated
            ? new ArrayList<>(fullRollups.subList(fullRollups.size() - DAILY_BUCKET_LIMIT, fullRollups.size()))
            : fullRollups;

        QualityOverviewAssembler.RouteTotals currentRoute =
            toRouteTotals(qualityOverviewMapper.selectRouteTotals(windowStart, null));
        QualityOverviewAssembler.RetrievalTotals currentRetrieval =
            toRetrievalTotals(qualityOverviewMapper.selectRetrievalTotals(windowStart, null));

        QualityOverviewAssembler.RouteTotals previousRoute = null;
        QualityOverviewAssembler.RetrievalTotals previousRetrieval = null;
        if (comparable) {
            Date previousStart = resolveWindowStart(windowDays * 2);
            previousRoute = toRouteTotals(
                qualityOverviewMapper.selectRouteTotals(previousStart, windowStart));
            previousRetrieval = toRetrievalTotals(
                qualityOverviewMapper.selectRetrievalTotals(previousStart, windowStart));
        }

        List<QualityOverviewAssembler.ChannelExecutionAggregate> channelExecutions =
            toChannelExecutionAggregates(safeRows(qualityOverviewMapper.selectChannelExecutionAggregates(windowStart)));
        List<QualityOverviewAssembler.RetrievalSnapshotAggregate> retrievalSnapshots =
            toRetrievalSnapshotAggregates(safeRows(qualityOverviewMapper.selectRetrievalSnapshotAggregates(windowStart)));
        List<QualityOverviewAssembler.DocumentTaskAggregate> documentTasks =
            toDocumentTaskAggregates(safeRows(qualityOverviewMapper.selectDocumentTaskAggregates(windowStart)));

        QualityOverviewVo vo = new QualityOverviewVo();
        vo.setWindowDays(String.valueOf(windowDays));
        vo.setWindowLabel(QualityOverviewAssembler.resolveWindowLabel(windowDays));
        vo.setComparable(comparable ? "1" : "0");
        vo.setTruncated(truncated ? "1" : "0");
        vo.setMetrics(buildMetrics(rollups, currentRoute, previousRoute, currentRetrieval, previousRetrieval));
        vo.setRouteTrend(routeTrend);
        vo.setChannelProfiles(
            QualityOverviewAssembler.buildChannelProfiles(channelExecutions, retrievalSnapshots));
        vo.setRouteDecision(QualityOverviewAssembler.buildRouteDecision(currentRoute));
        vo.setDocProcessing(QualityOverviewAssembler.buildDocProcessing(documentTasks));
        return vo;
    }

    private List<QualityOverviewVo.KpiMetric> buildMetrics(
        List<QualityOverviewAssembler.DailyRollup> rollups,
        QualityOverviewAssembler.RouteTotals currentRoute,
        QualityOverviewAssembler.RouteTotals previousRoute,
        QualityOverviewAssembler.RetrievalTotals currentRetrieval,
        QualityOverviewAssembler.RetrievalTotals previousRetrieval) {

        List<QualityOverviewVo.KpiMetric> metrics = new ArrayList<>();

        metrics.add(QualityOverviewAssembler.buildMetric(
            "route-total", "知识路由总量", "count",
            String.valueOf(currentRoute.totalCount()),
            previousRoute == null ? null : String.valueOf(previousRoute.totalCount()),
            true, "auto 与 shadow 两种模式的路由次数合计",
            QualityOverviewAssembler.buildTotalCountSparkline(rollups)));

        metrics.add(QualityOverviewAssembler.buildMetric(
            "route-success-rate", "路由成功率", "rate",
            QualityOverviewAssembler.formatRate(currentRoute.successCount(), currentRoute.totalCount()),
            previousRoute == null ? null
                : QualityOverviewAssembler.formatRate(previousRoute.successCount(), previousRoute.totalCount()),
            true, "route_status 为成功的次数占路由总量之比",
            QualityOverviewAssembler.buildSuccessRateSparkline(rollups)));

        metrics.add(QualityOverviewAssembler.buildMetric(
            "route-confidence", "平均置信度", "score",
            QualityOverviewAssembler.formatConfidence(currentRoute.averageConfidence()),
            previousRoute == null ? null
                : QualityOverviewAssembler.formatConfidence(previousRoute.averageConfidence()),
            true, "路由整体置信度按路由次数加权平均，取值 0 到 1",
            QualityOverviewAssembler.buildConfidenceSparkline(rollups)));

        metrics.add(QualityOverviewAssembler.buildMetric(
            "retrieval-hit-rate", "检索选入率", "rate",
            QualityOverviewAssembler.formatRate(
                currentRetrieval.selectedSnapshotCount(), currentRetrieval.snapshotCount()),
            previousRetrieval == null ? null
                : QualityOverviewAssembler.formatRate(
                    previousRetrieval.selectedSnapshotCount(), previousRetrieval.snapshotCount()),
            true, "检索候选中最终选入 Prompt 的比例",
            List.of()));

        return metrics;
    }

    private List<QualityOverviewAssembler.RouteDailyAggregate> toRouteDailyAggregates(List<Map<String, Object>> rows) {
        List<QualityOverviewAssembler.RouteDailyAggregate> aggregates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            LocalDate date = readDate(row, "traceDate");
            String mode = readText(row, "routeMode");
            if (date == null || mode.isBlank()) {
                continue;
            }
            aggregates.add(new QualityOverviewAssembler.RouteDailyAggregate(
                date,
                mode,
                readLong(row, "totalCount"),
                readLong(row, "successCount"),
                readLong(row, "lowConfidenceCount"),
                readLong(row, "failedCount"),
                readDouble(row, "averageConfidence")
            ));
        }
        return aggregates;
    }

    private List<QualityOverviewAssembler.ChannelExecutionAggregate> toChannelExecutionAggregates(
        List<Map<String, Object>> rows) {
        List<QualityOverviewAssembler.ChannelExecutionAggregate> aggregates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String channelType = readText(row, "channelType");
            if (channelType.isBlank()) {
                continue;
            }
            aggregates.add(new QualityOverviewAssembler.ChannelExecutionAggregate(
                channelType,
                readLong(row, "executionCount"),
                readLong(row, "recalledCount"),
                readLong(row, "acceptedCount"),
                readLong(row, "selectedCount")
            ));
        }
        return aggregates;
    }

    private List<QualityOverviewAssembler.RetrievalSnapshotAggregate> toRetrievalSnapshotAggregates(
        List<Map<String, Object>> rows) {
        List<QualityOverviewAssembler.RetrievalSnapshotAggregate> aggregates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String channelType = readText(row, "channelType");
            if (channelType.isBlank()) {
                continue;
            }
            aggregates.add(new QualityOverviewAssembler.RetrievalSnapshotAggregate(
                channelType,
                readLong(row, "snapshotCount"),
                readLong(row, "selectedSnapshotCount"),
                readLong(row, "resolvedCount")
            ));
        }
        return aggregates;
    }

    private List<QualityOverviewAssembler.DocumentTaskAggregate> toDocumentTaskAggregates(
        List<Map<String, Object>> rows) {
        List<QualityOverviewAssembler.DocumentTaskAggregate> aggregates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            LocalDate date = readDate(row, "taskDate");
            if (date == null) {
                continue;
            }
            aggregates.add(new QualityOverviewAssembler.DocumentTaskAggregate(
                date,
                (int) readLong(row, "taskType"),
                readLong(row, "taskCount"),
                readDouble(row, "averageCostMs")
            ));
        }
        return aggregates;
    }

    private QualityOverviewAssembler.RouteTotals toRouteTotals(Map<String, Object> row) {
        return new QualityOverviewAssembler.RouteTotals(
            readLong(row, "totalCount"),
            readLong(row, "successCount"),
            readLong(row, "lowConfidenceCount"),
            readLong(row, "failedCount"),
            readDouble(row, "averageConfidence")
        );
    }

    private QualityOverviewAssembler.RetrievalTotals toRetrievalTotals(Map<String, Object> row) {
        return new QualityOverviewAssembler.RetrievalTotals(
            readLong(row, "snapshotCount"),
            readLong(row, "selectedSnapshotCount")
        );
    }

    private int resolveWindowDays(String rawValue) {
        if (StrUtil.isBlank(rawValue)) {
            return DEFAULT_WINDOW_DAYS;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            if (parsed <= 0) {
                return 0;
            }
            return Math.min(parsed, MAX_WINDOW_DAYS);
        }
        catch (NumberFormatException exception) {
            return DEFAULT_WINDOW_DAYS;
        }
    }

    private Date resolveWindowStart(int windowDays) {
        return windowDays <= 0 ? null : Date.from(Instant.now().minus(Duration.ofDays(windowDays)));
    }

    private LocalDate readDate(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        try {
            return LocalDate.parse(String.valueOf(value).trim().substring(0, 10));
        }
        catch (RuntimeException exception) {
            return null;
        }
    }

    private long readLong(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value).trim()));
        }
        catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private double readDouble(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value instanceof Number number) {
            return Math.max(0D, number.doubleValue());
        }
        if (value == null) {
            return 0D;
        }
        try {
            return Math.max(0D, Double.parseDouble(String.valueOf(value).trim()));
        }
        catch (NumberFormatException exception) {
            return 0D;
        }
    }

    private String readText(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<Map<String, Object>> safeRows(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }
}
