package org.smartledge.ai.chatagent.rag.retrieve.channel;

import org.smartledge.ai.chatagent.rag.model.RetrievalExecutionRequest;
import org.smartledge.ai.chatagent.rag.service.DocumentRetrieveRequestFactory;
import org.smartledge.ai.rag.runtime.port.DocumentEvidencePort;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.enums.RetrievalChannelEnum;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @description: 向量检索通道
 * @author: Song
 **/

@Component
public class VectorRetrievalChannel implements RetrievalChannel {

    private final DocumentEvidencePort documentKnowledgeService;
    private final DocumentRetrieveRequestFactory documentRetrieveRequestFactory;

    public VectorRetrievalChannel(DocumentEvidencePort documentKnowledgeService,
                                  DocumentRetrieveRequestFactory documentRetrieveRequestFactory) {
        this.documentKnowledgeService = documentKnowledgeService;
        this.documentRetrieveRequestFactory = documentRetrieveRequestFactory;
    }

    @Override
    public String channelName() {
        return RetrievalChannelEnum.VECTOR.getName();
    }

    @Override
    public RetrievalChannelResult retrieve(RetrievalExecutionRequest request) {

        List<RetrievalDocument> documentList = documentKnowledgeService.vectorSearch(
            documentRetrieveRequestFactory.build(request, channelName())
        );
        double minimumScore = request.requireChannel(channelName()).minimumScore();
        List<RetrievalDocument> accepted = documentList == null ? List.of() : documentList.stream()
            .filter(document -> {
                Double score = resolveScore(document);
                return score != null && score >= minimumScore;
            })
            .toList();
        return new RetrievalChannelResult(
            channelName(), accepted
        );
    }

    private Double resolveScore(RetrievalDocument document) {
        if (document == null) {
            return null;
        }
        Object metadataScore = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        return metadataScore instanceof Number number ? number.doubleValue() : document.getScore();
    }
}
