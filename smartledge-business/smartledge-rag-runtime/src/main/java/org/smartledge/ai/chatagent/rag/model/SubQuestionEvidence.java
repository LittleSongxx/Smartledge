package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.List;

/**
 * @description: 单个子问题的证据容器
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubQuestionEvidence {

    private int subQuestionIndex;

    private String subQuestion;

    private List<RetrievalDocument> sourceDocuments;

    private List<RetrievalDocument> contextDocuments;

    private List<SearchReference> references;

    private List<SubQuestionChannelTrace> channelTraces;

    private Integer fusedCandidateCount;

    private Integer parentCandidateCount;

    private Integer rerankedCandidateCount;

    private EvidenceSelectionLedger evidenceSelectionLedger;

    private ObservationPersistence observationPersistence;
}
