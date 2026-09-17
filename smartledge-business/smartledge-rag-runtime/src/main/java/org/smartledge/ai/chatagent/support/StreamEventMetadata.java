package org.smartledge.ai.chatagent.support;

/**
 * @description: 支撑组件
 * @author: Song
 **/

public record StreamEventMetadata(
    String conversationId,
    Long exchangeId
) {
}
