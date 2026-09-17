package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import com.baidu.fsg.uid.UidGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.manage.data.SuperAgentDocumentTaskLog;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentTaskLogMapper;
import org.smartledge.ai.manage.service.DocumentTaskLogService;
import org.smartledge.ai.manage.support.DerivedRowTenantScope;
import org.smartledge.ai.manage.support.DocumentTenantLookup;
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
    private final DocumentTenantLookup documentTenantLookup;

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
        Long tenantId = documentTenantLookup.tenantOfDocument(documentId);
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalStateException("写入任务日志缺少父文档租户，documentId=" + documentId);
        }
        SuperAgentDocumentTaskLog log = new SuperAgentDocumentTaskLog();
        log.setId(uidGenerator.getUid());
        log.setTenantId(tenantId);
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
        DerivedRowTenantScope.runPerDocument(tenantId, () -> taskLogMapper.insert(log));
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
