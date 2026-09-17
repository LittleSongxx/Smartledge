package org.smartledge.ai.manage.model;

import lombok.Data;
import org.smartledge.ai.rag.runtime.config.ChatAgentProperties;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.rag.runtime.model.RagRuntimeOptions;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.smartledge.ai.knowledge.augmentation.config.GraphRagBuildProperties;

/**
 * Complete, typed system configuration snapshot persisted in smartledge_system_config.
 */
@Data
public class SystemConfigSnapshot {

    private MvcAsyncOptions mvcAsync = new MvcAsyncOptions();
    private RagRuntimeOptions ragRuntime = RagRuntimeOptions.defaults();
    private ChatAgentProperties chat = new ChatAgentProperties();
    private ChatRagProperties rag = new ChatRagProperties();
    private GraphRagBuildProperties graphRagBuild = new GraphRagBuildProperties();
    private GraphRagExtractionOptions graphRagExtraction = new GraphRagExtractionOptions();
    private ToggleOptions graphRagCommunityReport = new ToggleOptions();
    private ToggleOptions graphRagEntityResolution = new ToggleOptions();
    private int raptorLlmConcurrency = 3;
    private ChunkEnrichmentOptions chunkEnrichment = new ChunkEnrichmentOptions();
    private AdminAuthOptions adminAuth = new AdminAuthOptions();
    private PreviewModeOptions previewMode = new PreviewModeOptions();
    private EvaluationSnapshotOptions evaluationSnapshot = new EvaluationSnapshotOptions();
    private RetrievalProbeOptions retrievalProbe = new RetrievalProbeOptions();
    private RagToolsOptions ragTools = new RagToolsOptions();
    private GraphRagDiagnosticsOptions graphRagDiagnostics = new GraphRagDiagnosticsOptions();
    private GraphRagExecutionOptions graphRagExecution = new GraphRagExecutionOptions();
    private ChunkEnrichmentExecutionOptions chunkEnrichmentExecution = new ChunkEnrichmentExecutionOptions();
    private DocumentManageProperties.IndexBuild indexBuild = new DocumentManageProperties.IndexBuild();
    private DocumentManageProperties.Chunk chunk = new DocumentManageProperties.Chunk();

    public static SystemConfigSnapshot defaults() {
        SystemConfigSnapshot snapshot = new SystemConfigSnapshot();

        ChatRagProperties rag = snapshot.getRag();
        rag.getRewriteOptions().setTemperature(0.2D);
        rag.setNoEvidenceReply("当前没有从当前文档中检索到足够证据，暂时不能给出可靠结论。你可以补充更具体的章节名、标题或关键词后再试。");

        snapshot.getGraphRagBuild().setLeaseTtlSeconds(6000);
        snapshot.getIndexBuild().setEmbeddingBatchSize(5);
        snapshot.getIndexBuild().setElasticsearchRefreshWait(Boolean.TRUE);
        return snapshot;
    }

    @Data
    public static class MvcAsyncOptions {
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 64;
        private int keepAliveSeconds = 60;
        private int shutdownAwaitSeconds = 10;
    }

    @Data
    public static class ToggleOptions {
        private boolean enabled = true;
    }

    @Data
    public static class GraphRagExtractionOptions {
        // Must stay in sync with augmentation GraphRagExtractionOptions and with
        // sql/表数据/Mysql/init_system_config.sql. See that class for why 1 / 4600.
        private int batchChunkLimit = 1;
        private int inputTokenBudget = 4600;

        private int modelContextTokens = 16384;

        private int outputReserve = 4096;

        private int promptReserve = 1024;

        private int modelResponseMaxBytes = 1048576;

        private int maxEntityNameChars = 500;

        private long maxDocumentBytes = 4000000L;
        private int maxQuoteChars = 180;
        private int maxReasonChars = 240;
        private int maxUnitTextChars = 420;
    }

    @Data
    public static class ChunkEnrichmentOptions {
        private boolean enabled = true;
        private int maxKeywords = 8;
        private int maxQuestions = 4;
        private int maxKeywordChars = 32;
        private int maxQuestionChars = 40;
        private int batchChunkLimit = 6;
        private int batchTokenLimit = 2400;
    }

    @Data
    public static class AdminAuthOptions {
        private String tokenSecret;
        private long tokenExpireMinutes = 720L;
    }

    @Data
    public static class PreviewModeOptions {
        private boolean enabled = false;
        private String message = "线上环境为只读展示模式，仅开放浏览与检索能力";
    }

    @Data
    public static class EvaluationSnapshotOptions {
        private boolean enabled = true;
        private int maxBatchSize = 20;
        private int requestsPerMinute = 30;
        private String promptVersion = "prompt-v1";
    }

    @Data
    public static class RetrievalProbeOptions {
        private boolean enabled = false;
        private int requestsPerMinute = 10;
        private int maxQueryLength = 2000;
        private int maxResultCount = 100;
    }

    @Data
    public static class RagToolsOptions {
        private String baseUrl = "http://127.0.0.1:18080";
        private int connectTimeoutMs = 3000;
        private int documentParseReadTimeoutMs = 600000;
        private int graphExtractReadTimeoutMs = 110000;
        private int graphExtractionResponseMaxBytes = 16777216;
        private int raptorBuildReadTimeoutMs = 600000;
    }

    @Data
    public static class GraphRagDiagnosticsOptions {
        private boolean enabled = true;
        private String directory = "logs/graph-rag-diagnostics";
        private int maxTextChars = 4096;
        private int maxFileBytes = 2097152;
        private int maxFiles = 20;
    }

    @Data
    public static class GraphRagExecutionOptions {
        private int workerThreads = 6;
        private int queueCapacity = 12;
        private int documentConcurrency = 6;
        private int maxBatchAttempts = 3;
        private long retryBackoffMillis = 1000L;
        private long documentBudgetMillis = 1200000L;
        private long toolBudgetMillis = 100000L;
        private long reserveMillis = 9000L;
        private int maxSplitDepth = 3;
        private int maxSplits = 32;
        private int maxTimeoutSplitsPerSource = 2;
        private int maxBatches = 1024;
        private long maxCandidateBytes = 16777216L;
        private int maxObservationBytes = 32768;
    }

    @Data
    public static class ChunkEnrichmentExecutionOptions {
        private int workerThreads = 6;
        private int queueCapacity = 12;
        private int documentConcurrency = 6;
        private long batchTimeoutMillis = 200000L;
        private long documentBudgetMillis = 660000L;
        private long pollMillis = 90L;
    }
}
