package org.smartledge.ai.manage.support;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 判断某文档的派生索引行是否过期（S22 批次 3）。
 *
 * <p>背景：一次成功的构建只为**一个** {@code task_id} 写入 chunk / parent_block / structure_node /
 * kg_* / raptor_node。失败或重复的构建各自留下一套行，同一文档因此会累积多套派生数据
 * （实测文档 {@code 2522855131016618028} 有 5 个任务的 622 行 chunk 与 622 行向量）。</p>
 *
 * <p>过期口径只有一个：{@code smartledge_document.last_index_task_id} 是"当前有效集"的唯一权威
 * （检索也按它收窄，见 {@code RetrievalPlanAssembler}），因此该文档下**除它以外**的任务行都是过期行。
 * 本类只做纯判定，不执行删除：删除是不可逆动作，必须由带授权的一次性脚本执行。</p>
 *
 * <h2>边界</h2>
 *
 * <ul>
 *   <li>{@code lastIndexTaskId} 缺失（null/非正数）时**不判定任何行过期**：拿不到权威就什么都不删，
 *       否则会把唯一一套数据删掉。</li>
 *   <li>当前任务 id 永远不被判为过期，即使它出现在入参里。</li>
 * </ul>
 */
public final class StaleDerivedIndexRowPolicy {

    private StaleDerivedIndexRowPolicy() {
    }

    /**
     * 从给定任务集合里挑出过期任务 id。
     *
     * @param lastIndexTaskId 文档当前有效的索引任务（{@code smartledge_document.last_index_task_id}）
     * @param candidateTaskIds 该文档实际存在派生行的任务 id
     * @return 过期任务 id（保持入参顺序、去重）；权威缺失或没有过期任务时返回空集合
     */
    public static Set<Long> staleTaskIds(Long lastIndexTaskId, Collection<Long> candidateTaskIds) {
        if (lastIndexTaskId == null || lastIndexTaskId <= 0 || candidateTaskIds == null) {
            return Set.of();
        }
        Set<Long> stale = new LinkedHashSet<>();
        for (Long taskId : candidateTaskIds) {
            if (taskId != null && taskId > 0 && !Objects.equals(taskId, lastIndexTaskId)) {
                stale.add(taskId);
            }
        }
        return stale;
    }

    /** 单个任务是否过期（与 {@link #staleTaskIds} 同一口径）。 */
    public static boolean isStale(Long lastIndexTaskId, Long candidateTaskId) {
        return !staleTaskIds(lastIndexTaskId, candidateTaskId == null ? null : Set.of(candidateTaskId)).isEmpty();
    }
}
