package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.data.SuperAgentDocumentTaskLog;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentTaskLogService {

    SuperAgentDocumentTaskLog saveLog(Long taskId,
                                      Long documentId,
                                      Integer stageType,
                                      Integer eventType,
                                      Integer logLevel,
                                      Integer operatorType,
                                      Long operatorId,
                                      String content,
                                      Object detail);
}
