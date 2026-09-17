package org.smartledge.ai.manage.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @description: Mapper层
 * @author: Song
 **/
@Mapper
public interface QualityOverviewMapper {

    /**
     * 知识路由按天 + mode 聚合。沿用 SuperAgentKgEntityMapper 的 @Select + text block 范式。
     * 只统计有 mode 的行，mode 由后端写入，不在此推断。
     */
    @Select("""
        <script>
        SELECT
            DATE(trace_row.create_time)                         AS traceDate,
            trace_row.mode                                      AS routeMode,
            COUNT(*)                                            AS totalCount,
            SUM(trace_row.route_status = 1)                     AS successCount,
            SUM(trace_row.route_status = 2)                     AS lowConfidenceCount,
            SUM(trace_row.route_status = 3)                     AS failedCount,
            COALESCE(AVG(trace_row.confidence), 0)              AS averageConfidence
        FROM smartledge_knowledge_route_trace trace_row
        WHERE trace_row.status = 1
          AND trace_row.mode IS NOT NULL
          AND trace_row.create_time IS NOT NULL
        <if test="windowStart != null">
          AND trace_row.create_time &gt;= #{windowStart}
        </if>
        GROUP BY DATE(trace_row.create_time), trace_row.mode
        ORDER BY traceDate ASC, routeMode ASC
        </script>
        """)
    List<Map<String, Object>> selectRouteDailyAggregates(@Param("windowStart") Date windowStart);

    /**
     * 知识路由窗口整体聚合，用于半圆仪表与 KPI 当前值/环比值。
     * windowEnd 为空表示不设上界；比较上一窗口时传入上一窗口的边界。
     */
    @Select("""
        <script>
        SELECT
            COUNT(*)                                            AS totalCount,
            SUM(trace_row.route_status = 1)                     AS successCount,
            SUM(trace_row.route_status = 2)                     AS lowConfidenceCount,
            SUM(trace_row.route_status = 3)                     AS failedCount,
            COALESCE(AVG(trace_row.confidence), 0)              AS averageConfidence
        FROM smartledge_knowledge_route_trace trace_row
        WHERE trace_row.status = 1
          AND trace_row.mode IS NOT NULL
          AND trace_row.create_time IS NOT NULL
        <if test="windowStart != null">
          AND trace_row.create_time &gt;= #{windowStart}
        </if>
        <if test="windowEnd != null">
          AND trace_row.create_time &lt; #{windowEnd}
        </if>
        </script>
        """)
    Map<String, Object> selectRouteTotals(@Param("windowStart") Date windowStart,
                                         @Param("windowEnd") Date windowEnd);

    /**
     * 检索通道执行聚合：召回数与最终选入数来自通道执行计数器。
     */
    @Select("""
        <script>
        SELECT
            execution_row.channel_type                          AS channelType,
            COUNT(*)                                            AS executionCount,
            COALESCE(SUM(execution_row.recalled_count), 0)      AS recalledCount,
            COALESCE(SUM(execution_row.accepted_count), 0)      AS acceptedCount,
            COALESCE(SUM(execution_row.final_selected_count), 0) AS selectedCount
        FROM smartledge_chat_channel_execution execution_row
        WHERE execution_row.status = 1
        <if test="windowStart != null">
          AND execution_row.create_time &gt;= #{windowStart}
        </if>
        GROUP BY execution_row.channel_type
        ORDER BY recalledCount DESC, execution_row.channel_type ASC
        </script>
        """)
    List<Map<String, Object>> selectChannelExecutionAggregates(@Param("windowStart") Date windowStart);

    /**
     * 检索结果快照聚合：证据可解析率与命中率的分子分母都来自快照行。
     */
    @Select("""
        <script>
        SELECT
            result_row.channel_type                             AS channelType,
            COUNT(*)                                            AS snapshotCount,
            SUM(result_row.is_selected = 1)                     AS selectedSnapshotCount,
            SUM(result_row.source_evidence_resolved = 1)        AS resolvedCount
        FROM smartledge_chat_retrieval_result result_row
        WHERE result_row.status = 1
        <if test="windowStart != null">
          AND result_row.create_time &gt;= #{windowStart}
        </if>
        GROUP BY result_row.channel_type
        </script>
        """)
    List<Map<String, Object>> selectRetrievalSnapshotAggregates(@Param("windowStart") Date windowStart);

    /**
     * 检索命中率窗口整体聚合，用于 KPI 当前值与环比值。
     */
    @Select("""
        <script>
        SELECT
            COUNT(*)                                            AS snapshotCount,
            SUM(result_row.is_selected = 1)                     AS selectedSnapshotCount
        FROM smartledge_chat_retrieval_result result_row
        WHERE result_row.status = 1
        <if test="windowStart != null">
          AND result_row.create_time &gt;= #{windowStart}
        </if>
        <if test="windowEnd != null">
          AND result_row.create_time &lt; #{windowEnd}
        </if>
        </script>
        """)
    Map<String, Object> selectRetrievalTotals(@Param("windowStart") Date windowStart,
                                              @Param("windowEnd") Date windowEnd);

    /**
     * 文档任务按天 + 类型聚合耗时。task_type 1 解析路由、2 构建索引。
     * 只统计成功任务，失败任务的耗时不代表正常处理成本。
     */
    @Select("""
        <script>
        SELECT
            DATE(task_row.create_time)                          AS taskDate,
            task_row.task_type                                  AS taskType,
            COUNT(*)                                            AS taskCount,
            COALESCE(AVG(
                CASE WHEN task_row.cost_millis &gt; 0
                     THEN task_row.cost_millis END), 0)          AS averageCostMs
        FROM smartledge_document_task task_row
        WHERE task_row.status = 1
          AND task_row.task_status = 3
          AND task_row.create_time IS NOT NULL
        <if test="windowStart != null">
          AND task_row.create_time &gt;= #{windowStart}
        </if>
        GROUP BY DATE(task_row.create_time), task_row.task_type
        ORDER BY taskDate ASC, taskType ASC
        </script>
        """)
    List<Map<String, Object>> selectDocumentTaskAggregates(@Param("windowStart") Date windowStart);
}
