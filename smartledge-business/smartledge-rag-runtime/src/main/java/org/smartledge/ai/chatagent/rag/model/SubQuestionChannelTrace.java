package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 单个子问题在某个检索通道上的执行痕迹
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubQuestionChannelTrace {

    private String channelName;

    private String retrievalIntent;

    private Double channelWeight;

    private int recalledCount;

    private int acceptedCount;
}
