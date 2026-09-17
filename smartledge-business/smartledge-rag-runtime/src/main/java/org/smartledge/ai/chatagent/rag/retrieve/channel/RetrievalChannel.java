package org.smartledge.ai.chatagent.rag.retrieve.channel;

import org.smartledge.ai.chatagent.rag.model.RetrievalExecutionRequest;

/**
 * @description: 检索通道抽象
 * @author: Song
 **/

public interface RetrievalChannel {

    String channelName();

    RetrievalChannelResult retrieve(RetrievalExecutionRequest request);
}
