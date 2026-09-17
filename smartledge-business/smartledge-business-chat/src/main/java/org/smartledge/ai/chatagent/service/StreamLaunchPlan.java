package org.smartledge.ai.chatagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.enums.ChatQueryMode;

import java.time.LocalDate;

/**
 * @description: 服务层
 * @author: Song
 **/

@Data
@AllArgsConstructor
public class StreamLaunchPlan {

    private final String question;

    private final String conversationId;

    private final ChatQueryMode chatMode;

    private final Long selectedDocumentId;

    private final String selectedDocumentName;

    private final Long selectedTaskId;

    private final KnowledgeBaseSelectionSnapshot knowledgeBaseSelectionSnapshot;

    private final String leaseKey;

    private final String leaseOwnerToken;

    private final LocalDate currentDate;

    private final String currentDateText;
}
