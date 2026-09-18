package org.smartledge.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.enums.ChatTurnStatus;

import java.util.Date;
import java.util.List;

/**
 * @description: 视图对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationExchangeView {

    private long exchangeId;
    private String question;
    private String answer;
    private List<String> thinkingSteps;
    private List<SearchReference> references;
    private List<String> recommendations;
    private List<String> usedTools;
    private ChatDebugTrace debugTrace;
    private ChatTurnStatus status;
    private String errorMessage;
    private Long firstResponseTimeMs;
    private Long totalResponseTimeMs;
    private String knowledgeBaseSelectionMode;
    private List<String> selectedKnowledgeBaseIds;
    private List<String> selectedKnowledgeBaseNames;
    private String retrievalConfigSnapshotJson;
    private Date createTime;
    private Date editTime;
    /** 用户反馈评分 "UP"/"DOWN"；无反馈为 null。由业务层在视图装配后补充。 */
    private String feedbackRating;
    private String feedbackComment;
}
