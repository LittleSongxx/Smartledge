package org.smartledge.ai.manage.mq;

import org.smartledge.ai.knowledge.indexing.port.IndexingMessagingPort;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档索引链路的 AMQP 拓扑声明与监听容器配置。
 *
 * <p>所有队列、交换机、绑定和路由键都从 {@link DocumentMessagingTopology} 取值，
 * 与生产者、消费者共用同一个解析结果，因此不再存在「声明的名字与实际使用的名字不一致」的可能。</p>
 *
 * <p>业务队列绑定死信交换机：消费重试耗尽后消息进入死信队列，由
 * {@code DocumentMessageConsumer#consumeDeadLetter} 记录失败，不再静默丢失。</p>
 */
@EnableRabbit
@Configuration
@EnableConfigurationProperties(DocumentMessagingProperties.class)
public class DocumentMessagingConfiguration {

    @Bean
    public DocumentMessagingTopology documentMessagingTopology(DocumentMessagingProperties properties,
                                                              IndexingMessagingPort indexingMessagingPort) {
        return new DocumentMessagingTopology(properties.getResourcePrefix(),
            indexingMessagingPort == null ? null : indexingMessagingPort.currentQueues());
    }

    @Bean
    public DirectExchange documentManageExchange(DocumentMessagingTopology topology) {
        return new DirectExchange(topology.manageExchange(), true, false);
    }

    @Bean
    public DirectExchange documentDeadLetterExchange(DocumentMessagingTopology topology) {
        return new DirectExchange(topology.deadLetterExchange(), true, false);
    }

    @Bean
    public Queue documentParseRouteQueue(DocumentMessagingTopology topology) {
        return businessQueue(topology.parseRouteQueue(), topology);
    }

    @Bean
    public Queue documentIndexBuildQueue(DocumentMessagingTopology topology) {
        return businessQueue(topology.indexBuildQueue(), topology);
    }

    @Bean
    public Queue documentDeadLetterQueue(DocumentMessagingTopology topology) {
        return QueueBuilder.durable(topology.deadLetterQueue()).build();
    }

    @Bean
    public Binding documentParseRouteBinding(DocumentMessagingTopology topology,
                                             @Qualifier("documentParseRouteQueue") Queue documentParseRouteQueue,
                                             @Qualifier("documentManageExchange") DirectExchange documentManageExchange) {
        return BindingBuilder.bind(documentParseRouteQueue)
            .to(documentManageExchange)
            .with(topology.parseRouteRoutingKey());
    }

    @Bean
    public Binding documentIndexBuildBinding(DocumentMessagingTopology topology,
                                             @Qualifier("documentIndexBuildQueue") Queue documentIndexBuildQueue,
                                             @Qualifier("documentManageExchange") DirectExchange documentManageExchange) {
        return BindingBuilder.bind(documentIndexBuildQueue)
            .to(documentManageExchange)
            .with(topology.indexBuildRoutingKey());
    }

    @Bean
    public Binding documentDeadLetterBinding(DocumentMessagingTopology topology,
                                             @Qualifier("documentDeadLetterQueue") Queue documentDeadLetterQueue,
                                             @Qualifier("documentDeadLetterExchange") DirectExchange documentDeadLetterExchange) {
        return BindingBuilder.bind(documentDeadLetterQueue)
            .to(documentDeadLetterExchange)
            .with(topology.deadLetterRoutingKey());
    }

    /**
     * 监听容器工厂。
     *
     * <p>{@code defaultRequeueRejected=false} 配合重试恢复器，保证重试耗尽后消息被拒绝且不重回原队列，
     * 从而按队列参数进入死信交换机。基础队列的消费者只负责重试，不做无限重入。</p>
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                              DocumentMessagingProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
            .maxAttempts(Math.max(1, properties.getMaxAttempts()))
            .backOffOptions(properties.getRetryInitialIntervalMillis(),
                properties.getRetryMultiplier(),
                properties.getRetryMaxIntervalMillis())
            .recoverer(new RejectAndDontRequeueRecoverer())
            .build());
        return factory;
    }

    private Queue businessQueue(String name, DocumentMessagingTopology topology) {
        return QueueBuilder.durable(name)
            .deadLetterExchange(topology.deadLetterExchange())
            .deadLetterRoutingKey(topology.deadLetterTargetRoutingKey())
            .build();
    }
}
