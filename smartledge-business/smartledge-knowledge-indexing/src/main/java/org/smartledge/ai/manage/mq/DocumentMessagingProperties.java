package org.smartledge.ai.manage.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档索引链路的消息层配置。
 *
 * <p>只承载消息机制本身的参数：资源前缀、投递确认、消费重试和对账调度。
 * 队列基础名由 {@code IndexingMessagingPort} 提供，连接参数由 {@code spring.rabbitmq} 提供。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.manage.messaging")
public class DocumentMessagingProperties {

    /** 资源名前缀，必须与 Spring 应用前缀一致，只应用一次。 */
    private String resourcePrefix = "smartledge-agent";

    /** 等待 broker publisher confirm 的超时时间，单位毫秒。 */
    private Long publishConfirmTimeoutMillis = 5000L;

    /** 单条消息在进入死信队列前允许的消费尝试次数。 */
    private Integer maxAttempts = 3;

    /** 第一次重试的退避间隔，单位毫秒。 */
    private Long retryInitialIntervalMillis = 2000L;

    /** 重试退避的倍增系数。 */
    private Double retryMultiplier = 2.0D;

    /** 重试退避的最大间隔，单位毫秒。 */
    private Long retryMaxIntervalMillis = 30000L;

    /** 对账任务配置。 */
    private Reconcile reconcile = new Reconcile();

    @Data
    public static class Reconcile {

        /** 是否启用对账任务。 */
        private Boolean enabled = Boolean.TRUE;

        /** 对账任务的执行间隔，单位毫秒。 */
        private Long intervalMillis = 60000L;

        /** 任务创建后多久仍未被执行才判定为投递丢失并补投，单位毫秒。 */
        private Long dispatchGraceMillis = 120000L;

        /**
         * 失联静默窗口，单位毫秒。
         * 有 {@code graphRagBuild.lastCheckpointTime} 时从最近心跳起算；
         * 没有可解析心跳时从 {@code startTime} 起算。
         */
        private Long staleTaskTimeoutMillis = 7200000L;

        /** 单次对账最多处理的任务数，避免一次性放大。 */
        private Integer batchSize = 200;

        /** 同一任务允许被补投的最大次数；超过后直接判定失败，保证不会无限重投。 */
        private Integer maxRedispatchAttempts = 5;
    }
}
