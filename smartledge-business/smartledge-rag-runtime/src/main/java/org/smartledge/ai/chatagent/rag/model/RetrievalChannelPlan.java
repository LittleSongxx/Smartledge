package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个检索通道的完整执行参数。
 *
 * <p>本类只承载 enabled、预算和权重等执行参数。通道是否参与由 KB 配置、知识范围和产物存在性决定，
 * 不由问题关键词决定；各通道统一从这里读取 scope、query、budget 和 weight。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalChannelPlan {

    /** 通道名，对应 {@code RetrievalChannelEnum.getName()} 与 {@code RetrievalChannel.channelName()}。 */
    private String channelName;

    /** 是否参与本轮检索。默认由 KB 配置启用 + 范围/产物存在性决定，不由问题关键词决定。 */
    private boolean enabled;

    /** 通道请求 topK。 */
    private int topK;

    /** 通道超时，毫秒。 */
    private long timeoutMs;

    /** 通道进入统一候选池的预算。 */
    private int budget;

    /** weighted hybrid 基础权重，由 plan assembler 从本轮受控配置一次写入。 */
    private double weight;

    /** 通道绝对最低分；不适用的通道为 0。 */
    private double minimumScore;

    /** 相对本通道最高分的最低比例；不适用的通道为 0。 */
    private double relativeScoreFloor;
}
