package org.smartledge.ai.manage.mq;

import org.smartledge.ai.knowledge.indexing.port.IndexingMessagingPort;
import org.smartledge.ai.knowledge.indexing.port.IndexingMessagingPort.MessagingQueues;

/**
 * 文档索引链路所有 AMQP 资源名的<b>唯一</b>解析点。
 *
 * <p>生产者、消费者和拓扑声明都必须从本类取名字。此前 Kafka 实现里三处各自计算名字
 * （生产者加前缀、消费者用 SpEL 加前缀、NewTopic 不加前缀），导致 {@code auto-create-topics}
 * 建的 topic 从未被真正使用。这里把前缀拼接收敛到一处。</p>
 *
 * <p>本类是纯函数式组件，不依赖 Spring 容器，可直接构造用于单元测试。</p>
 */
public final class DocumentMessagingTopology {

    /** 业务消息交换机名后缀。 */
    private static final String MANAGE_EXCHANGE_SUFFIX = "document-manage";

    /** 死信交换机名后缀。与死信队列刻意不同名，避免运维把两者混淆。 */
    private static final String DEAD_LETTER_EXCHANGE_SUFFIX = "document-dead-letter-exchange";

    /** 死信队列名后缀。 */
    private static final String DEAD_LETTER_QUEUE_SUFFIX = "document-dead-letter";

    /** 解析路由消息的路由键。 */
    private static final String PARSE_ROUTE_ROUTING_KEY = "document-parse-route";

    /** 索引构建消息的路由键。 */
    private static final String INDEX_BUILD_ROUTING_KEY = "document-index-build";

    /** 死信消息的路由键。 */
    private static final String DEAD_LETTER_ROUTING_KEY = "document-dead-letter";

    /**
     * 消息类型头。
     *
     * <p>死信队列里的消息是从死信交换机投递过来的，消费端看到的队列名是死信队列，
     * 无法据此判断原始业务类型。因此发布时把头写成业务路由键，死信处理据此还原类型。</p>
     */
    public static final String MESSAGE_TYPE_HEADER = "x-document-message-type";

    private final String prefix;

    private final MessagingQueues queues;

    public DocumentMessagingTopology(String prefix, MessagingQueues queues) {
        String resolvedPrefix = prefix == null ? "" : prefix.trim();
        while (resolvedPrefix.endsWith("-")) {
            resolvedPrefix = resolvedPrefix.substring(0, resolvedPrefix.length() - 1);
        }
        this.prefix = resolvedPrefix;
        this.queues = queues == null ? MessagingQueues.defaults() : queues;
    }

    public static DocumentMessagingTopology defaults(String prefix) {
        return new DocumentMessagingTopology(prefix, MessagingQueues.defaults());
    }

    public String parseRouteQueue() {
        return qualified(queues.parseRouteQueue());
    }

    public String indexBuildQueue() {
        return qualified(queues.indexBuildQueue());
    }

    public String deadLetterQueue() {
        return qualified(DEAD_LETTER_QUEUE_SUFFIX);
    }

    public String manageExchange() {
        return qualified(MANAGE_EXCHANGE_SUFFIX);
    }

    public String deadLetterExchange() {
        return qualified(DEAD_LETTER_EXCHANGE_SUFFIX);
    }

    public String parseRouteRoutingKey() {
        return PARSE_ROUTE_ROUTING_KEY;
    }

    public String indexBuildRoutingKey() {
        return INDEX_BUILD_ROUTING_KEY;
    }

    public String deadLetterRoutingKey() {
        return DEAD_LETTER_ROUTING_KEY;
    }

    /** 业务队列共用的死信路由键；队列参数与死信队列绑定必须使用同一个值。 */
    public String deadLetterTargetRoutingKey() {
        return DEAD_LETTER_ROUTING_KEY;
    }

    private String qualified(String base) {
        if (prefix.isEmpty()) {
            return base;
        }
        return prefix + "-" + base;
    }
}
