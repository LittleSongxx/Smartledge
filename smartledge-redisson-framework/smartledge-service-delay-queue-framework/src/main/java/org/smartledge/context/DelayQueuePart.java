package org.smartledge.context;

import org.smartledge.core.ConsumerTask;
import lombok.Data;

/**
 * @description: 消息主题
 * @author: Song
 **/
@Data
public class DelayQueuePart {

    private final DelayQueueBasePart delayQueueBasePart;

    private final ConsumerTask consumerTask;

    public DelayQueuePart(DelayQueueBasePart delayQueueBasePart, ConsumerTask consumerTask){
        this.delayQueueBasePart = delayQueueBasePart;
        this.consumerTask = consumerTask;
    }
}
