package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import com.baidu.fsg.uid.UidGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.manage.data.SuperAgentDocumentTaskLog;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskLogMapper;
import org.smartledge.ai.manage.service.DocumentTaskLogService;
import org.smartledge.enums.BusinessStatus;
import org.springframework.stereotype.Service;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@AllArgsConstructor
@Service
public class DocumentTaskLogServiceImpl implements DocumentTaskLogService {

    private final SuperAgentDocumentTaskLogMapper taskLogMapper;
    private final ObjectMapper objectMapper;
    private final UidGenerator uidGenerator;

    @Override
    public SuperAgentDocumentTaskLog saveLog(Long taskId,
                                             Long documentId,
                                             Integer stageType,
                                             Integer eventType,
                                             Integer logLevel,
                                             Integer operatorType,
                                             Long operatorId,
                                             String content,
                                             Object detail) {
        SuperAgentDocumentTaskLog log = new SuperAgentDocumentTaskLog();
        log.setId(uidGenerator.getUid());
        log.setTaskId(taskId);
        log.setDocumentId(documentId);
        log.setStageType(stageType);
        log.setEventType(eventType);
        log.setLogLevel(logLevel);
        log.setOperatorType(operatorType);
        log.setOperatorId(operatorId);
        log.setContent(content);
        log.setDetailJson(toJson(detail));
        log.setStatus(BusinessStatus.YES.getCode());
        taskLogMapper.insert(log);
        return log;
    }

    private String toJson(Object detail) {
        if (detail == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        }
        catch (JsonProcessingException exception) {
            return String.valueOf(detail);
        }
    }
}
