package org.smartledge.ai.chatagent.evaluation.probe;

import lombok.Builder;
import lombok.Value;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger;
import org.smartledge.ai.chatagent.rag.model.SubQuestionChannelTrace;

import java.util.List;

@Value
@Builder
public class RetrievalProbeSubQuestion {
    int subQuestionIndex;
    String subQuestion;
    List<SubQuestionChannelTrace> channelTraces;
    int fusedCandidateCount;
    int parentCandidateCount;
    int rerankedCandidateCount;
    List<String> sourceCandidateIdentities;
    List<String> contextCandidateIdentities;
    EvidenceSelectionLedger evidenceSelectionLedger;
}
