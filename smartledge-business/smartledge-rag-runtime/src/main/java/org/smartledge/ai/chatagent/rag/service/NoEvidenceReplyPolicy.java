package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.chatagent.rag.model.QueryType;
import org.smartledge.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.springframework.stereotype.Service;

/** Owns the user-facing reply selected when document retrieval yields no source evidence. */
@Service
@RequiredArgsConstructor
public class NoEvidenceReplyPolicy {

    private final ChatRagProperties properties;

    public String resolve(boolean requiresFreshSearch, QueryUnderstandingResult understanding) {
        QueryType queryType = understanding == null || understanding.getQueryType() == null
            ? QueryType.DOCUMENT_QA
            : understanding.getQueryType();
        if (queryType == QueryType.CAPABILITY_QUERY) {
            return "当前你正在使用“当前文档问答”模式，我会优先基于所选文档回答。这个问题更像是在询问助手能力，而不是当前文档内容。如果你想了解我能做什么，请切换到“开放式提问”模式。";
        }
        if (queryType == QueryType.OPEN_CHAT || requiresFreshSearch) {
            return "当前你正在使用“当前文档问答”模式，我只能基于所选文档回答。这个问题更像开放式提问，例如天气、最新信息或一般交流。如果你想继续问这类问题，请切换到“开放式提问”模式。";
        }
        return defaultReply();
    }

    public String defaultReply() {
        return StrUtil.blankToDefault(
            properties.getNoEvidenceReply(),
            "当前没有从当前文档中检索到足够证据，暂时不能给出可靠结论。你可以补充更具体的标题、术语或关键词后再试。"
        );
    }
}
