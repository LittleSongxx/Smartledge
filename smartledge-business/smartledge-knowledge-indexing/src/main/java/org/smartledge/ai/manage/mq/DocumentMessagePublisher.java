package org.smartledge.ai.manage.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.mq.message.DocumentIndexBuildMessage;
import org.smartledge.ai.manage.mq.message.DocumentParseRouteMessage;
import org.smartledge.enums.DocumentManageCode;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 文档索引链路的消息发布者。
 *
 * <p>发布到业务交换机并带上路由键，通过 publisher confirm 同步确认投递结果。
 * 确认失败、消息不可路由或等待超时都会抛出 {@link SuperAgentFrameException}，
 * 由调用方决定标记任务失败或依赖对账任务补投，禁止静默丢弃。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentMessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    private final DocumentMessagingTopology topology;

    private final DocumentMessagingProperties messagingProperties;

    private final ObjectMapper objectMapper;

    public void publishParseRoute(DocumentParseRouteMessage message) {
        publish(topology.manageExchange(), topology.parseRouteRoutingKey(), message, "解析路由");
    }

    public void publishIndexBuild(DocumentIndexBuildMessage message) {
        publish(topology.manageExchange(), topology.indexBuildRoutingKey(), message, "索引构建");
    }

    private void publish(String exchange, String routingKey, Object payload, String label) {
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.MESSAGE_PUBLISH_FAILED.getCode(),
                label + "消息序列化失败: " + exception.getMessage(), exception);
        }

        CorrelationData correlationData = new CorrelationData(label);
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, body, message -> {
                // 死信消费看不到原始队列名，类型必须随消息携带。
                message.getMessageProperties().setHeader(DocumentMessagingTopology.MESSAGE_TYPE_HEADER, routingKey);
                return message;
            }, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                .get(messagingProperties.getPublishConfirmTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm == null ? "未收到 broker 确认" : confirm.getReason();
                throw new SuperAgentFrameException(DocumentManageCode.MESSAGE_PUBLISH_FAILED.getCode(),
                    label + "消息未被 broker 确认: " + reason);
            }
            if (correlationData.getReturned() != null) {
                throw new SuperAgentFrameException(DocumentManageCode.MESSAGE_PUBLISH_FAILED.getCode(),
                    label + "消息不可路由: " + correlationData.getReturned().getReplyText());
            }
        }
        catch (SuperAgentFrameException exception) {
            throw exception;
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SuperAgentFrameException(DocumentManageCode.MESSAGE_PUBLISH_FAILED.getCode(),
                label + "消息投递被中断: " + exception.getMessage(), exception);
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.MESSAGE_PUBLISH_FAILED.getCode(),
                label + "消息投递失败: " + exception.getMessage(), exception);
        }
    }
}
