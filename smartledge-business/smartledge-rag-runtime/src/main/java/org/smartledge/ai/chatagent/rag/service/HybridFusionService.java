package org.smartledge.ai.chatagent.rag.service;

import org.smartledge.ai.chatagent.rag.model.RetrievalIntent;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.model.EvidenceSelectionLedger.IdentityResolutionStatus;
import org.smartledge.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.smartledge.ai.chatagent.rag.support.EvidenceCandidateIdentity;
import org.smartledge.ai.chatagent.rag.support.EvidenceIdentityResolver;
import org.smartledge.ai.chatagent.rag.support.EvidenceQualityFeatures;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.enums.RetrievalChannelEnum;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 通道加权 RRF（k=60）。原始通道分、metadataBoost 只写入观测 metadata，不进入最终加法。
 *
 * <p>Fusion does not reserve, replace, or select final evidence.</p>
 */
public class HybridFusionService {

    private static final int RRF_K = 60;

    private final RankFeatureService rankFeatureService;

    public HybridFusionService(RankFeatureService rankFeatureService) {
        this.rankFeatureService = rankFeatureService;
    }

    public List<RetrievalDocument> fuse(List<RetrievalChannelResult> channelResults, RetrievalPlan plan) {
        Map<String, CandidateHolder> holders = new LinkedHashMap<>();

        for (RetrievalChannelResult retrievalChannelResult : channelResults) {
            accumulateWeightedRrf(retrievalChannelResult, holders, plan);
        }

        List<CandidateHolder> sortedHolders = holders.values().stream()
            .peek(this::finishRrfScore)
            .peek(this::writeHybridMetadata)
            .sorted((left, right) -> Double.compare(right.score, left.score))
            .toList();
        return selectHybridCandidates(sortedHolders, plan).stream()
            .map(holder -> holder.document)
            .toList();
    }

    private List<CandidateHolder> selectHybridCandidates(List<CandidateHolder> sortedHolders,
                                                         RetrievalPlan plan) {
        if (sortedHolders == null || sortedHolders.isEmpty()) {
            return List.of();
        }
        int candidateTopK = plan == null ? 0 : Math.max(plan.getCandidateWindow(), 0);
        return candidateTopK == 0
            ? List.of()
            : sortedHolders.stream().limit(candidateTopK).toList();
    }

    private void writeHybridMetadata(CandidateHolder holder) {
        holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.SCORE, holder.score);
        holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.RRF_SCORE, holder.rrfScore);
        holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.HYBRID_SCORE, holder.score);
        holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.METADATA_BOOST, holder.metadataBoost);
        if (holder.vectorScore != null) {
            holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.VECTOR_SCORE, holder.vectorScore);
        }
        if (holder.keywordScore != null) {
            holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE, holder.keywordScore);
        }
        holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL,
            holder.channels.size() > 1 ? "hybrid" : holder.channels.iterator().next());
    }

    private void accumulateWeightedRrf(RetrievalChannelResult channelResult,
                                       Map<String, CandidateHolder> holders,
                                       RetrievalPlan plan) {
        if (channelResult == null || channelResult.getDocuments() == null) {
            return;
        }
        List<RetrievalDocument> documents = channelResult.getDocuments();
        for (int rank = 0; rank < documents.size(); rank++) {
            RetrievalDocument document = documents.get(rank);
            EvidenceCandidateIdentity.ensure(document);
            String fusionIdentity = fusionIdentity(document);

            double channelWeight = resolveChannelWeight(channelResult.getChannelName(), plan);
            double weightedRrf = channelWeight * (1D / (RRF_K + rank + 1));
            Double originalScore = resolveScore(document);
            document.getMetadata().put(DocumentKnowledgeMetadataKeys.RETRIEVAL_INTENT, resolveRetrievalIntent(plan).name());
            document.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL_WEIGHT, channelWeight);
            if (RetrievalChannelEnum.VECTOR.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                document.getMetadata().put(DocumentKnowledgeMetadataKeys.VECTOR_SCORE, originalScore);
            }
            if (RetrievalChannelEnum.KEYWORD.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                document.getMetadata().put(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE, originalScore);
            }
            CandidateHolder holder = holders.computeIfAbsent(fusionIdentity, ignored -> new CandidateHolder(document));
            mergeGraphRagMetadata(holder, document);
            holder.rrfScore += weightedRrf;
            holder.metadataBoost = Math.max(holder.metadataBoost, rankFeatureService.calculateMetadataBoost(document, plan));
            holder.channels.add(channelResult.getChannelName());
            if (RetrievalChannelEnum.VECTOR.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                holder.vectorScore = originalScore;
            }
            if (RetrievalChannelEnum.KEYWORD.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                holder.keywordScore = originalScore;
            }
        }
    }

    private String fusionIdentity(RetrievalDocument document) {
        IdentityResolutionStatus status = EvidenceCandidateIdentity.identityResolutionStatus(document);
        return switch (status) {
            case CITATION_RESOLVED -> "CITATION:" + EvidenceIdentityResolver.citationIdentityValue(document);
            case CONTEXT_RESOLVED -> "CONTEXT:" + EvidenceIdentityResolver.contextIdentityValue(document);
            case CANDIDATE_FALLBACK -> "CANDIDATE:" + EvidenceCandidateIdentity.candidateId(document);
        };
    }

    private void finishRrfScore(CandidateHolder holder) {
        holder.score = holder.rrfScore;
    }

    /** Fusion consumes the channel weight already frozen into the RetrievalPlan. */
    public double resolveChannelWeight(String channelName, RetrievalPlan plan) {
        if (plan == null || channelName == null) {
            return 1D;
        }
        return Math.max(0D, plan.requireChannel(channelName).getWeight());
    }

    private void mergeGraphRagMetadata(CandidateHolder holder, RetrievalDocument candidate) {
        if (holder == null || candidate == null || !isGraphRagMetadata(candidate.getMetadata())) {
            return;
        }
        Map<String, Object> holderMetadata = holder.document.getMetadata();
        Map<String, Object> candidateMetadata = candidate.getMetadata();
        if (graphRagMetadataPriority(candidate) > graphRagMetadataPriority(holder.document)) {
            copyGraphRagMetadata(candidateMetadata, holderMetadata);
            return;
        }
        for (String key : DocumentKnowledgeMetadataKeys.GRAPH_RAG_METADATA_KEYS) {
            Object existing = holderMetadata.get(key);
            Object incoming = candidateMetadata.get(key);
            if (isMeaningfulMetadataValue(incoming) && !isMeaningfulMetadataValue(existing)) {
                holderMetadata.put(key, incoming);
            }
        }
    }

    private void copyGraphRagMetadata(Map<String, Object> source, Map<String, Object> target) {
        for (String key : DocumentKnowledgeMetadataKeys.GRAPH_RAG_METADATA_KEYS) {
            Object value = source.get(key);
            if (isMeaningfulMetadataValue(value)) {
                target.put(key, value);
            }
        }
    }

    private double graphRagMetadataPriority(RetrievalDocument document) {
        if (document == null || document.getMetadata() == null || !isGraphRagMetadata(document.getMetadata())) {
            return 0D;
        }
        return EvidenceQualityFeatures.resolve(document).metadataPriority(finalDocumentScore(document));
    }

    private boolean isGraphRagMetadata(Map<String, Object> metadata) {
        String channel = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL));
        return RetrievalChannelEnum.GRAPH_RAG.getName().equals(channel)
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID) != null
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY))
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID) != null
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null;
    }

    private RetrievalIntent resolveRetrievalIntent(RetrievalPlan plan) {
        return plan == null || plan.getPrimaryIntent() == null ? RetrievalIntent.GENERAL : plan.getPrimaryIntent();
    }

    private double finalDocumentScore(RetrievalDocument document) {
        if (document == null) {
            return 0D;
        }
        Double score = resolveScore(document);
        if (score != null) {
            return score;
        }
        return document.getScore() == null ? 0D : document.getScore();
    }

    private Double resolveScore(RetrievalDocument document) {
        if (document == null) {
            return null;
        }
        Object metadataScore = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        if (metadataScore instanceof Number number) {
            return number.doubleValue();
        }
        return document.getScore();
    }

    private boolean isMeaningfulMetadataValue(Object value) {
        if (value == null) {
            return false;
        }
        return !(value instanceof String text) || !text.isBlank();
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static class CandidateHolder {

        private final RetrievalDocument document;
        private final LinkedHashSet<String> channels = new LinkedHashSet<>();
        private double rrfScore;
        private double metadataBoost;
        private double score;
        private Double vectorScore;
        private Double keywordScore;

        private CandidateHolder(RetrievalDocument input) {
            Map<String, Object> metadata = new LinkedHashMap<>(input.getMetadata());
            metadata.remove(DocumentKnowledgeMetadataKeys.CANDIDATE_ID);
            metadata.remove(DocumentKnowledgeMetadataKeys.LINEAGE_IDENTITY);
            metadata.remove(DocumentKnowledgeMetadataKeys.IDENTITY_RESOLUTION_STATUS);
            this.document = RetrievalDocument.builder()
                .id(input.getId())
                .text(input.getText())
                .metadata(metadata)
                .score(input.getScore())
                .build();
            EvidenceCandidateIdentity.ensure(this.document);
        }
    }
}
