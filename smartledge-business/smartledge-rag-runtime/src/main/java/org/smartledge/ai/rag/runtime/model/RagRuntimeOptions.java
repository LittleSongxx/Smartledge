package org.smartledge.ai.rag.runtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRuntimeOptions {

    private int vectorTopK;

    private int keywordTopK;

    private int graphRagTopK;

    private int graphRagMaxHops;

    private int raptorTopK;

    private int raptorSourceChunkTopK;

    private int candidateTopK;

    private int rerankCandidateTopK;

    private int finalTopK;

    @Builder.Default
    private boolean rerankEnabled = true;

    private long channelTimeoutMs;

    private long subQuestionTimeoutMs;

    private double minVectorSimilarity;

    private double keywordRelativeScoreFloor;

    private boolean keywordChannelEnabled;

    private boolean tableChannelEnabled;

    private boolean graphRagChannelEnabled;

    private boolean raptorChannelEnabled;

    @Builder.Default
    private boolean forceExpensiveChannels = false;

    private HybridOptions hybrid;

    @Builder.Default
    private List<String> kbConfigConflictFields = new ArrayList<>();

    public static RagRuntimeOptions defaults() {
        return RagRuntimeOptions.builder()
            .vectorTopK(10)
            .keywordTopK(10)
            .graphRagTopK(5)
            .graphRagMaxHops(2)
            .raptorTopK(5)
            .raptorSourceChunkTopK(3)
            .candidateTopK(40)
            .rerankCandidateTopK(24)
            .finalTopK(6)
            .rerankEnabled(true)
            .channelTimeoutMs(25000L)
            .subQuestionTimeoutMs(40000L)
            .minVectorSimilarity(0.45D)
            .keywordRelativeScoreFloor(0.35D)
            .keywordChannelEnabled(true)
            .tableChannelEnabled(true)
            .graphRagChannelEnabled(true)
            .raptorChannelEnabled(true)
            .forceExpensiveChannels(false)
            .hybrid(HybridOptions.defaults())
            .kbConfigConflictFields(new ArrayList<>())
            .build();
    }

    public static RagRuntimeOptions from(ChatRagProperties properties) {
        if (properties == null) {
            return defaults();
        }
        ChatRagProperties.HybridProperties hybridProperties = properties.getHybrid();
        return RagRuntimeOptions.builder()
            .vectorTopK(properties.getVectorTopK())
            .keywordTopK(properties.getKeywordTopK())
            .graphRagTopK(properties.getGraphRagTopK())
            .graphRagMaxHops(properties.getGraphRagMaxHops())
            .raptorTopK(properties.getRaptorTopK())
            .raptorSourceChunkTopK(properties.getRaptorSourceChunkTopK())
            .candidateTopK(properties.getCandidateTopK())
            .rerankCandidateTopK(properties.getRerankCandidateTopK())
            .finalTopK(properties.getFinalTopK())
            .rerankEnabled(properties.isRerankEnabled())
            .channelTimeoutMs(properties.getChannelTimeoutMs())
            .subQuestionTimeoutMs(properties.getSubQuestionTimeoutMs())
            .minVectorSimilarity(properties.getMinVectorSimilarity())
            .keywordRelativeScoreFloor(properties.getKeywordRelativeScoreFloor())
            .keywordChannelEnabled(properties.isKeywordChannelEnabled())
            .tableChannelEnabled(properties.isTableChannelEnabled())
            .graphRagChannelEnabled(properties.isGraphRagChannelEnabled())
            .raptorChannelEnabled(properties.isRaptorChannelEnabled())
            .forceExpensiveChannels(false)
            .hybrid(HybridOptions.from(hybridProperties))
            .kbConfigConflictFields(new ArrayList<>())
            .build();
    }

    public RagRuntimeOptions deepCopy() {
        HybridOptions copiedHybrid = hybrid == null ? HybridOptions.from(null) : HybridOptions.builder()
            .vectorWeight(hybrid.getVectorWeight())
            .keywordWeight(hybrid.getKeywordWeight())
            .tableWeight(hybrid.getTableWeight())
            .graphRagWeight(hybrid.getGraphRagWeight())
            .raptorWeight(hybrid.getRaptorWeight())
            .rankWeight(hybrid.getRankWeight())
            .originalScoreWeight(hybrid.getOriginalScoreWeight())
            .metadataBoostWeight(hybrid.getMetadataBoostWeight())
            .maxMetadataBoost(hybrid.getMaxMetadataBoost())
            .build();
        return RagRuntimeOptions.builder()
            .vectorTopK(vectorTopK)
            .keywordTopK(keywordTopK)
            .graphRagTopK(graphRagTopK)
            .graphRagMaxHops(graphRagMaxHops)
            .raptorTopK(raptorTopK)
            .raptorSourceChunkTopK(raptorSourceChunkTopK)
            .candidateTopK(candidateTopK)
            .rerankCandidateTopK(rerankCandidateTopK)
            .finalTopK(finalTopK)
            .rerankEnabled(rerankEnabled)
            .channelTimeoutMs(channelTimeoutMs)
            .subQuestionTimeoutMs(subQuestionTimeoutMs)
            .minVectorSimilarity(minVectorSimilarity)
            .keywordRelativeScoreFloor(keywordRelativeScoreFloor)
            .keywordChannelEnabled(keywordChannelEnabled)
            .tableChannelEnabled(tableChannelEnabled)
            .graphRagChannelEnabled(graphRagChannelEnabled)
            .raptorChannelEnabled(raptorChannelEnabled)
            .forceExpensiveChannels(forceExpensiveChannels)
            .hybrid(copiedHybrid)
            .kbConfigConflictFields(kbConfigConflictFields == null ? new ArrayList<>() : new ArrayList<>(kbConfigConflictFields))
            .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HybridOptions {

        private double vectorWeight;

        private double keywordWeight;

        private double tableWeight;

        private double graphRagWeight;

        private double raptorWeight;

        private double rankWeight;

        private double originalScoreWeight;

        private double metadataBoostWeight;

        private double maxMetadataBoost;

        public static HybridOptions defaults() {
            return HybridOptions.builder()
                .vectorWeight(1.0D)
                .keywordWeight(1.0D)
                .tableWeight(1.2D)
                .graphRagWeight(1.1D)
                .raptorWeight(1.05D)
                .rankWeight(1.0D)
                .originalScoreWeight(0.08D)
                .metadataBoostWeight(0.04D)
                .maxMetadataBoost(1.0D)
                .build();
        }

        public static HybridOptions from(ChatRagProperties.HybridProperties properties) {
            if (properties == null) {
                return defaults();
            }
            return HybridOptions.builder()
                .vectorWeight(properties.getVectorWeight())
                .keywordWeight(properties.getKeywordWeight())
                .tableWeight(properties.getTableWeight())
                .graphRagWeight(properties.getGraphRagWeight())
                .raptorWeight(properties.getRaptorWeight())
                .rankWeight(properties.getRankWeight())
                .originalScoreWeight(properties.getOriginalScoreWeight())
                .metadataBoostWeight(properties.getMetadataBoostWeight())
                .maxMetadataBoost(properties.getMaxMetadataBoost())
                .build();
        }
    }
}
