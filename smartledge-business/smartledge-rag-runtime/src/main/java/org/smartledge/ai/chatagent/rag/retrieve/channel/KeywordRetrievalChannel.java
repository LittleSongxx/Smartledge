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
 * @description: 关键词检索通道
 * @author: Song
 **/

@Component
public class KeywordRetrievalChannel implements RetrievalChannel {

    private final DocumentEvidencePort documentKnowledgeService;
    private final DocumentRetrieveRequestFactory documentRetrieveRequestFactory;

    public KeywordRetrievalChannel(DocumentEvidencePort documentKnowledgeService,
                                   DocumentRetrieveRequestFactory documentRetrieveRequestFactory) {
        this.documentKnowledgeService = documentKnowledgeService;
        this.documentRetrieveRequestFactory = documentRetrieveRequestFactory;
    }

    @Override
    public String channelName() {
        return RetrievalChannelEnum.KEYWORD.getName();
    }

    @Override
    public RetrievalChannelResult retrieve(RetrievalExecutionRequest request) {
        List<RetrievalDocument> documentList = documentKnowledgeService.keywordSearch(
            documentRetrieveRequestFactory.build(request, channelName())
        );
        List<RetrievalDocument> candidates = documentList == null ? List.of() : documentList;
        Double topScore = candidates.stream()
            .map(this::resolveScore)
            .filter(java.util.Objects::nonNull)
            .max(Double::compareTo)
            .orElse(null);
        if (topScore == null || topScore <= 0D) {
            return new RetrievalChannelResult(channelName(), candidates);
        }
        double acceptedFloor = topScore * Math.max(0D,
            request.requireChannel(channelName()).relativeScoreFloor());
        List<RetrievalDocument> accepted = candidates.stream()
            .filter(document -> {
                Double score = resolveScore(document);
                return score != null && score >= acceptedFloor;
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
