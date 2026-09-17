package org.smartledge.ai.manage.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 消息组件
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIndexBuildMessage {

    private Long documentId;

    private Long taskId;

    private Long planId;
}
