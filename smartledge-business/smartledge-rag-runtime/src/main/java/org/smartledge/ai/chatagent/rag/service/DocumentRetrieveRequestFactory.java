package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.chatagent.rag.model.RetrievalExecutionRequest;
import org.smartledge.ai.rag.runtime.model.DocumentRetrieveFilters;
import org.smartledge.ai.rag.runtime.model.DocumentRetrieveRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/** Storage-protocol projection of the authoritative execution request. */
@Slf4j
@Component
public class DocumentRetrieveRequestFactory {

    public DocumentRetrieveRequest build(RetrievalExecutionRequest executionRequest, String channelName) {
        if (executionRequest == null) {
            throw new IllegalArgumentException("RetrievalExecutionRequest is required for document retrieval");
        }
        RetrievalExecutionRequest.ChannelSpec channel = executionRequest.requireChannel(channelName);
        ScopeHint scopeHint = resolveScopeHint(executionRequest.documentScope(), executionRequest.taskScope());
        DocumentRetrieveRequest request = new DocumentRetrieveRequest(
            firstNonBlank(executionRequest.sourceQuestion(), executionRequest.normalizedQuery()),
            executionRequest.executionQuery(),
            scopeHint.documentId(),
            scopeHint.taskId(),
            channel.topK(),
            projectFilters(executionRequest.filters()),
            executionRequest.contextHints()
        );
        request.setDocumentIds(executionRequest.documentScope());
        request.setTaskIds(executionRequest.taskScope());
        log.info("检索请求构造: originalSubQuestion='{}', retrievalQuery='{}', documentId={}, taskId={}, documentCount={}, sectionHints={}, yearHints={}, queryContextHints={}",
            StrUtil.blankToDefault(executionRequest.sourceQuestion(), "").trim(),
            request.getRetrievalQuery(),
            request.getDocumentId(),
            request.getTaskId(),
            request.resolvedDocumentIds().size(),
            request.getFilters() == null ? List.of() : request.getFilters().getSectionPathHints(),
            request.getFilters() == null ? List.of() : request.getFilters().getYearHints(),
            request.getQueryContextHints());
        return request;
    }

    private ScopeHint resolveScopeHint(List<Long> documentIds, List<Long> taskIds) {
        if (documentIds.size() == 1 && taskIds.size() == 1) {
            return new ScopeHint(documentIds.get(0), taskIds.get(0));
        }
        return new ScopeHint(null, null);
    }

    private DocumentRetrieveFilters projectFilters(RetrievalExecutionRequest.Filters filters) {
        return DocumentRetrieveFilters.builder()
            .documentNameHints(filters == null ? List.of() : filters.documentNameHints())
            .sectionPathHints(filters == null ? List.of() : filters.sectionPathHints())
            .yearHints(filters == null ? List.of() : filters.yearHints())
            .build();
    }

    private String firstNonBlank(String primary, String fallback) {
        return StrUtil.isNotBlank(primary) ? primary.trim() : StrUtil.blankToDefault(fallback, "").trim();
    }

    private record ScopeHint(Long documentId, Long taskId) {
    }
}
