package org.smartledge.ai.manage.service;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentAsyncProcessService {

    void handleParseRoute(Long documentId, Long taskId);

    void submitIndexBuild(Long documentId, Long taskId, Long planId);

    void handleIndexBuild(Long documentId, Long taskId, Long planId);

    /**
     * 处理进入死信队列的触发消息。
     *
     * <p>重试耗尽的触发消息必须落成任务失败记录，否则任务会永远停在待执行状态，
     * 使用者既看不到失败原因，也无法重新触发。本方法是「要么被执行、要么被记录」的落点。</p>
     *
     * @param messageType 发布时写入的消息类型，等于业务路由键；无法识别时为 null
     * @param payload     原始消息体
     */
    void handleDeadLetter(String messageType, String payload);
}
