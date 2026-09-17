package org.smartledge.ai.manage.mq;

import org.smartledge.ai.knowledge.indexing.port.IndexingMessagingPort.MessagingQueues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源命名不变量测试。
 *
 * <p>对应缺陷 S19-B6：旧 Kafka 实现里生产者加一次前缀、消费者用 SpEL 再加一次前缀、
 * {@code NewTopic} bean 不加前缀，三处名字不一致，导致自动建 topic 的开关实际无效。
 * 这些用例把「前缀只解析一次，且所有使用点取同一结果」固定下来。</p>
 */
class DocumentMessagingTopologyTest {

    private static final String PREFIX = "smartledge";

    private static DocumentMessagingTopology topology() {
        return new DocumentMessagingTopology(PREFIX,
            new MessagingQueues("document-parse-route", "document-index-build"));
    }

    @Test
    @DisplayName("前缀只应用一次，不出现重复前缀")
    void prefixAppliedExactlyOnce() {
        DocumentMessagingTopology topology = topology();

        assertThat(topology.parseRouteQueue()).isEqualTo("smartledge-document-parse-route");
        assertThat(topology.indexBuildQueue()).isEqualTo("smartledge-document-index-build");
        assertThat(topology.parseRouteQueue()).doesNotContain(PREFIX + "-" + PREFIX);
        assertThat(topology.indexBuildQueue()).doesNotContain(PREFIX + "-" + PREFIX);
    }

    @Test
    @DisplayName("业务交换机与死信资源都由同一解析点产生")
    void exchangeAndDeadLetterResourcesComeFromSameResolution() {
        DocumentMessagingTopology topology = topology();

        assertThat(topology.manageExchange()).isEqualTo("smartledge-document-manage");
        assertThat(topology.deadLetterExchange()).isEqualTo("smartledge-document-dead-letter-exchange");
        assertThat(topology.deadLetterQueue()).isEqualTo("smartledge-document-dead-letter");
        assertThat(topology.deadLetterQueue()).isNotEqualTo(topology.deadLetterExchange());
    }

    @Test
    @DisplayName("队列参数的死信路由键与死信绑定路由键必须相等")
    void deadLetterRoutingKeyIsSharedByQueueArgumentAndBinding() {
        DocumentMessagingTopology topology = topology();

        assertThat(topology.deadLetterTargetRoutingKey())
            .isEqualTo(topology.deadLetterRoutingKey())
            .isEqualTo("document-dead-letter");
    }

    @Test
    @DisplayName("解析路由与索引构建的路由键互不相同，且与基础队列名一致")
    void routingKeysAreDistinctAndMatchBaseNames() {
        DocumentMessagingTopology topology = topology();

        assertThat(topology.parseRouteRoutingKey()).isEqualTo("document-parse-route");
        assertThat(topology.indexBuildRoutingKey()).isEqualTo("document-index-build");
        assertThat(topology.parseRouteRoutingKey()).isNotEqualTo(topology.indexBuildRoutingKey());
    }

    @Test
    @DisplayName("前缀末尾多余的连字符会被归一化，不产生双连字符")
    void trailingHyphenInPrefixIsNormalized() {
        DocumentMessagingTopology topology = new DocumentMessagingTopology("smartledge-",
            new MessagingQueues("document-parse-route", "document-index-build"));

        assertThat(topology.parseRouteQueue()).isEqualTo("smartledge-document-parse-route");
        assertThat(topology.parseRouteQueue()).doesNotContain("--");
    }

    @Test
    @DisplayName("空前缀时直接使用基础名")
    void blankPrefixKeepsBaseName() {
        DocumentMessagingTopology topology = new DocumentMessagingTopology(" ",
            new MessagingQueues("document-parse-route", "document-index-build"));

        assertThat(topology.parseRouteQueue()).isEqualTo("document-parse-route");
    }

    @Test
    @DisplayName("基础名为空时回退到默认值，不回退到带前缀的历史名字")
    void blankBaseNameFallsBackToDefaultWithoutPrefix() {
        DocumentMessagingTopology topology = new DocumentMessagingTopology(PREFIX, new MessagingQueues(null, " "));

        assertThat(topology.parseRouteQueue()).isEqualTo("smartledge-document-parse-route");
        assertThat(topology.indexBuildQueue()).isEqualTo("smartledge-document-index-build");
    }

    @Test
    @DisplayName("同一拓扑实例重复取名字结果稳定")
    void namesAreStableAcrossCalls() {
        DocumentMessagingTopology topology = topology();

        assertThat(topology.indexBuildQueue()).isEqualTo(topology.indexBuildQueue());
        assertThat(topology.deadLetterQueue()).isEqualTo(topology.deadLetterQueue());
    }
}
