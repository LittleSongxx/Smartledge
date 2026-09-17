package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.chatagent.model.SearchReference;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 知识检索上下文
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagRetrievalContext {

    private String retrievalQuestion;

    private List<SubQuestionEvidence> subQuestionEvidenceList = new ArrayList<>();

    private List<String> retrievalNotes = new ArrayList<>();

    private List<String> usedChannels = new ArrayList<>();

    private List<RetrievalExecutionRequest> executionRequests = new ArrayList<>();

    private List<RetrievalExecutionRequest.Reconciliation> requestReconciliations = new ArrayList<>();

    public boolean isEmpty() {
        return subQuestionEvidenceList == null
            || subQuestionEvidenceList.stream().allMatch(item -> item.getReferences() == null || item.getReferences().isEmpty());
    }

    public List<SearchReference> flattenReferences() {
        if (subQuestionEvidenceList == null || subQuestionEvidenceList.isEmpty()) {
            return List.of();
        }
        List<SearchReference> references = new ArrayList<>();
        for (SubQuestionEvidence item : subQuestionEvidenceList) {
            if (item.getReferences() == null || item.getReferences().isEmpty()) {
                continue;
            }
            references.addAll(item.getReferences());
        }
        return references;
    }
}
