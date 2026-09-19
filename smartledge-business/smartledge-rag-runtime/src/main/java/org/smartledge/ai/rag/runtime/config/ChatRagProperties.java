package org.smartledge.ai.rag.runtime.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import org.smartledge.ai.rag.runtime.port.ChatRuntimeConfigProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @description: 配置属性
 * @author: Song
 **/

@Data
@Component
@ConfigurationProperties(prefix = "app.chat.rag")
public class ChatRagProperties {

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private ChatRuntimeConfigProvider runtimeConfigProvider;

    private boolean enabled = true;

    private boolean rewriteEnabled = true;

    private int rewriteHistoryTurns = 4;

    private RewriteOptionsProperties rewriteOptions = new RewriteOptionsProperties();

    private int maxSubQuestions = 4;

    private int vectorTopK = 10;

    private int keywordTopK = 10;

    private int graphRagTopK = 5;

    private int graphRagMaxHops = 2;

    private GraphRagQueryPlanProperties graphRagQueryPlan = new GraphRagQueryPlanProperties();

    private AutoRouteProperties autoRoute = new AutoRouteProperties();

    private int raptorTopK = 5;

    private int raptorSourceChunkTopK = 3;

    private int raptorMaxClusterSize = 6;

    private int raptorMaxLevels = 3;

    private boolean raptorLlmSummaryEnabled = true;

    private double raptorSummaryQualityFloor = 0.42D;

    private int candidateTopK = 40;

    private int rerankCandidateTopK = 24;

    private int finalTopK = 6;

    private double minVectorSimilarity = 0.45D;

    private double keywordRelativeScoreFloor = 0.35D;

    private double minEvidenceConfidence = 0D;

    private int parentEvidenceMaxChars = 2200;

    private int planningHistoryMaxChars = 1600;

    private int answerHistoryMaxChars = 1000;

    private int totalEvidenceMaxChars = 5200;

    private int perSubQuestionEvidenceMaxChars = 2200;

    private long channelTimeoutMs = 25000L;

    private long subQuestionTimeoutMs = 40000L;

    private boolean keywordChannelEnabled = true;

    private boolean tableChannelEnabled = true;

    private boolean graphRagChannelEnabled = true;

    private boolean raptorChannelEnabled = true;

    private HybridProperties hybrid = new HybridProperties();

    private boolean rerankEnabled = true;

    private String noEvidenceReply = "当前没有从已接入文档中检索到足够证据，暂时不能给出可靠结论。";

    private HistorySummaryProperties historySummary = new HistorySummaryProperties();

    private AgentKnowledgeProperties agentKnowledge = new AgentKnowledgeProperties();

    private LongTermMemoryProperties longTermMemory = new LongTermMemoryProperties();

    public boolean isEnabled() { return managed().enabled; }
    public boolean isRewriteEnabled() { return managed().rewriteEnabled; }
    public int getRewriteHistoryTurns() { return managed().rewriteHistoryTurns; }
    public RewriteOptionsProperties getRewriteOptions() { return managed().rewriteOptions; }
    public int getMaxSubQuestions() { return managed().maxSubQuestions; }
    public int getVectorTopK() { return managed().vectorTopK; }
    public int getKeywordTopK() { return managed().keywordTopK; }
    public int getGraphRagTopK() { return managed().graphRagTopK; }
    public int getGraphRagMaxHops() { return managed().graphRagMaxHops; }
    public GraphRagQueryPlanProperties getGraphRagQueryPlan() { return managed().graphRagQueryPlan; }
    public AutoRouteProperties getAutoRoute() { return managed().autoRoute; }
    public int getRaptorTopK() { return managed().raptorTopK; }
    public int getRaptorSourceChunkTopK() { return managed().raptorSourceChunkTopK; }
    public int getRaptorMaxClusterSize() { return managed().raptorMaxClusterSize; }
    public int getRaptorMaxLevels() { return managed().raptorMaxLevels; }
    public boolean isRaptorLlmSummaryEnabled() { return managed().raptorLlmSummaryEnabled; }
    public double getRaptorSummaryQualityFloor() { return managed().raptorSummaryQualityFloor; }
    public int getCandidateTopK() { return managed().candidateTopK; }
    public int getRerankCandidateTopK() { return managed().rerankCandidateTopK; }
    public int getFinalTopK() { return managed().finalTopK; }
    public double getMinVectorSimilarity() { return managed().minVectorSimilarity; }
    public double getKeywordRelativeScoreFloor() { return managed().keywordRelativeScoreFloor; }
    public double getMinEvidenceConfidence() { return managed().minEvidenceConfidence; }
    public int getParentEvidenceMaxChars() { return managed().parentEvidenceMaxChars; }
    public int getPlanningHistoryMaxChars() { return managed().planningHistoryMaxChars; }
    public int getAnswerHistoryMaxChars() { return managed().answerHistoryMaxChars; }
    public int getTotalEvidenceMaxChars() { return managed().totalEvidenceMaxChars; }
    public int getPerSubQuestionEvidenceMaxChars() { return managed().perSubQuestionEvidenceMaxChars; }
    public long getChannelTimeoutMs() { return managed().channelTimeoutMs; }
    public long getSubQuestionTimeoutMs() { return managed().subQuestionTimeoutMs; }
    public boolean isKeywordChannelEnabled() { return managed().keywordChannelEnabled; }
    public boolean isTableChannelEnabled() { return managed().tableChannelEnabled; }
    public boolean isGraphRagChannelEnabled() { return managed().graphRagChannelEnabled; }
    public boolean isRaptorChannelEnabled() { return managed().raptorChannelEnabled; }
    public HybridProperties getHybrid() { return managed().hybrid; }
    public boolean isRerankEnabled() { return managed().rerankEnabled; }
    public String getNoEvidenceReply() { return managed().noEvidenceReply; }
    public HistorySummaryProperties getHistorySummary() { return managed().historySummary; }
    public AgentKnowledgeProperties getAgentKnowledge() {
        AgentKnowledgeProperties value = managed().agentKnowledge;
        return value == null ? new AgentKnowledgeProperties() : value;
    }
    public LongTermMemoryProperties getLongTermMemory() {
        LongTermMemoryProperties value = managed().longTermMemory;
        return value == null ? new LongTermMemoryProperties() : value;
    }

    private ChatRagProperties managed() {
        if (runtimeConfigProvider == null) {
            return this;
        }
        ChatRagProperties managed = runtimeConfigProvider.currentRag();
        return managed == null || managed == this ? this : managed;
    }

    @Data
    public static class AgentKnowledgeProperties {

        private int topK = 6;

        private long timeoutMs = 15000L;
    }

    @Data
    public static class LongTermMemoryProperties {

        private int maxFacts = 12;

        private int maxFactChars = 240;
    }

    @Data
    public static class HistorySummaryProperties {

        private boolean enabled = true;

        private int keepRecentTurns = 4;

        private int compressionBatchTurns = 6;

        private int recentTranscriptMaxChars = 2200;

        private int summaryMaxChars = 1400;
    }

    @Data
    public static class RewriteOptionsProperties {

        private boolean enabled = true;

        private Double temperature = 0.1D;

        private Double topP = 0.3D;

        private Boolean thinking = Boolean.FALSE;
    }

    @Data
    public static class GraphRagQueryPlanProperties {

        private boolean enabled = true;
    }

    @Data
    public static class AutoRouteProperties {

        private double recommendationThreshold = 0.55D;
    }

    @Data
    public static class HybridProperties {

        private double vectorWeight = 1.0D;

        private double keywordWeight = 1.0D;

        private double tableWeight = 1.2D;

        private double graphRagWeight = 1.1D;

        private double raptorWeight = 1.05D;

        private double rankWeight = 1.0D;

        /** 仅观测/兼容配置，不参与加权 RRF。 */
        private double originalScoreWeight = 0.08D;

        private double metadataBoostWeight = 0.04D;

        private double maxMetadataBoost = 1.0D;
    }
}
