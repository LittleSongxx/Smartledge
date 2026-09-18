package org.smartledge.ai.manage.support;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务在途登记：本进程「已接手但尚未跑完」的触发任务集合。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>数据库里的任务状态只有 NEW/RUNNING/FAILED/SUCCESS，而 NEW 同时覆盖两种截然不同的处境：
 * 「触发消息已丢失，永远不会被执行」与「消息已消费、任务正在执行器队列里排队或正在执行」。
 * 对账任务看不见后者的存在，会把排队超过宽限期的任务当丢失消息反复补投，
 * 补投副本把执行器队列塞满后被拒绝、重试耗尽进死信，死信又把仍在排队的任务误标失败
 * （2026-09-19 线上 30 份文档一次性触发构建时 28 份被误杀即此链路）。</p>
 *
 * <h2>语义</h2>
 *
 * <ul>
 *   <li>登记发生在消息消费入口（解析）或提交执行器成功时（索引构建）；清除发生在执行结束（含异常）。</li>
 *   <li>登记是幂等去重：重复登记返回 false，调用方据此跳过重复提交。</li>
 *   <li>状态只存在于内存，与执行器/消费线程同生命周期：进程重启后登记与排队任务一起消失，
 *       对账任务恢复「消息可能丢失」的判断，不受影响。</li>
 * </ul>
 */
@Component
public class DocumentTaskInFlightRegistry {

    private final Set<Long> inFlightTaskIds = ConcurrentHashMap.newKeySet();

    /** 登记在途；已在途时返回 false（幂等去重）。 */
    public boolean markInFlight(Long taskId) {
        return taskId != null && inFlightTaskIds.add(taskId);
    }

    /** 执行结束（含异常）后清除登记。 */
    public void clear(Long taskId) {
        if (taskId != null) {
            inFlightTaskIds.remove(taskId);
        }
    }

    /** 任务是否在本进程排队或执行中。 */
    public boolean isInFlight(Long taskId) {
        return taskId != null && inFlightTaskIds.contains(taskId);
    }
}
