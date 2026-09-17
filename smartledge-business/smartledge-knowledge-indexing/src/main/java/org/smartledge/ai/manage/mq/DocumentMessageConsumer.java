package org.smartledge.ai.manage.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.mq.message.DocumentIndexBuildMessage;
import org.smartledge.ai.manage.mq.message.DocumentParseRouteMessage;
import org.smartledge.ai.manage.service.DocumentAsyncProcessService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 文档索引链路的消息消费者。
 *
 * <p>与旧 Kafka 实现的关键差异：这里<b>不吞异常</b>。监听方法抛出异常后由容器按
 * {@code DefaultRequeueRejected=false} 与重试拦截器处理，重试耗尽即进入死信队列，
 * 由 {@link #consumeDeadLetter} 记录失败，因此不会再出现「消费失败只写日志、消息静默丢失」。</p>
 *
 * <p>队列名通过 SpEL 从 {@code DocumentMessagingTopology} 取，与生产者、拓扑声明同源。</p>
 */
@Slf4j
@Component
public class DocumentMessageConsumer {

    private final DocumentAsyncProcessService asyncProcessService;

    private final ObjectMapper objectMapper;

    public DocumentMessageConsumer(DocumentAsyncProcessService asyncProcessService,
                                   ObjectMapper objectMapper) {
        this.asyncProcessService = asyncProcessService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "#{@documentMessagingTopology.parseRouteQueue()}")
    public void consumeParseRoute(String payload) throws Exception {

        DocumentParseRouteMessage message = objectMapper.readValue(payload, DocumentParseRouteMessage.class);
        asyncProcessService.handleParseRoute(message.getDocumentId(), message.getTaskId());
    }

    @RabbitListener(queues = "#{@documentMessagingTopology.indexBuildQueue()}")
    public void consumeIndexBuild(String payload) throws Exception {

        DocumentIndexBuildMessage message = objectMapper.readValue(payload, DocumentIndexBuildMessage.class);
        asyncProcessService.submitIndexBuild(message.getDocumentId(), message.getTaskId(), message.getPlanId());
    }

    /**
     * 死信消费者：把重试耗尽的触发消息落成任务失败记录，保证「要么被执行、要么被记录」。
     */
    @RabbitListener(queues = "#{@documentMessagingTopology.deadLetterQueue()}")
    public void consumeDeadLetter(Message message) {

        // 死信消息来自死信交换机，消费端队列名固定是死信队列，
        // 因此业务类型只能从发布时写入的消息头还原。
        Object messageType = message.getMessageProperties() == null
            ? null
            : message.getMessageProperties().getHeaders().get(DocumentMessagingTopology.MESSAGE_TYPE_HEADER);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        log.error("消息进入死信队列，messageType={}, payload={}", messageType, payload);
        try {
            asyncProcessService.handleDeadLetter(messageType == null ? null : messageType.toString(), payload);
        }
        catch (Exception exception) {
            log.error("死信记录失败，messageType={}, payload={}", messageType, payload, exception);
        }
    }
}
