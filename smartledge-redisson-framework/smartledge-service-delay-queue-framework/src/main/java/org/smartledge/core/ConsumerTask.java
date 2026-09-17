package org.smartledge.core;

/**
 * @description: 延迟队列 消费者接口
 * @author: Song
 **/
public interface ConsumerTask {

    void execute(String content);

    String topic();
}
