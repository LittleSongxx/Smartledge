package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @description: 视图对象
 * @author: Song
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QualityOverviewVo {

    private String windowDays;

    private String windowLabel;

    /**
     * 是否存在上一等长窗口可用于环比。全部时间窗没有上一窗口，环比为空。
     */
    private String comparable;

    /**
     * 日粒度序列是否因为硬上限被截断。
     */
    private String truncated;

    /**
     * 顶部 KPI 指标，含环比与 sparkline。
     */
    private List<KpiMetric> metrics;

    /**
     * 知识路由日粒度走势，auto 与 shadow 双序列。
     */
    private List<RouteTrendPoint> routeTrend;

    /**
     * 检索通道能力剖面，用于雷达图各轴。
     */
    private List<ChannelProfileItem> channelProfiles;

    /**
     * 路由裁决结构，用于半圆仪表。
     */
    private RouteDecision routeDecision;

    /**
     * 文档处理耗时，用于分组条形。
     */
    private DocProcessing docProcessing;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KpiMetric {

        private String key;

        private String label;

        /**
         * 当前窗口聚合值。rate 类为百分数字符串，count 类为整数字符串。
         */
        private String value;

        /**
         * 值的形态：count / rate / score。前端据此决定单位与格式。
         */
        private String valueType;

        /**
         * 上一等长窗口的同一指标值；不可比时为空串。
         */
        private String previousValue;

        /**
         * 环比变化量（当前减上一窗口，保留一位小数）；不可比时为空串。
         */
        private String delta;

        /**
         * 该指标是否越大越好。前端据此决定 delta 的语义色，不在前端硬编码指标名。
         */
        private String higherIsBetter;

        /**
         * 该指标的日粒度 sparkline 序列，与 routeTrend 同一批日期桶。
         */
        private List<SparkPoint> sparkline;

        /**
         * 指标分母说明，用于 hint 行，避免前端自行编造口径。
         */
        private String hint;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SparkPoint {

        private String date;

        /**
         * 该日取值；无样本日为空串，前端必须断点而不是补零。
         */
        private String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteTrendPoint {

        private String date;

        private String dateLabel;

        private String autoCount;

        private String autoSuccessRate;

        private String autoConfidence;

        private String shadowCount;

        private String shadowSuccessRate;

        private String shadowConfidence;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelProfileItem {

        private String channelType;

        private String channelName;

        /**
         * 通道原始召回数，雷达外圈。
         */
        private String recalledCount;

        /**
         * 最终选入 Prompt 数，雷达内圈。
         */
        private String selectedCount;

        /**
         * 选入率百分比，保留一位小数。
         */
        private String selectionRate;

        /**
         * 快照条数中已解析到真实可引用 source evidence 的占比百分比。
         */
        private String resolutionRate;

        private String snapshotCount;

        private String executionCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteDecision {

        private String total;

        private String success;

        private String lowConfidence;

        private String failed;

        private String successRate;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocProcessing {

        private String parseCount;

        private String indexCount;

        private String averageParseMs;

        private String averageIndexMs;

        /**
         * 建索引均值相对解析均值的倍数，保留一位小数；解析均值为 0 时为空串。
         */
        private String indexToParseRatio;

        private List<DocProcessingItem> items;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocProcessingItem {

        private String date;

        private String dateLabel;

        private String parseCount;

        private String averageParseMs;

        private String indexCount;

        private String averageIndexMs;
    }
}
