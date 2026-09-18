package org.smartledge.ai.knowledge.augmentation.config;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagExecutionConfigurationPort;

/** GraphRAG execution limits are loaded from the database-backed system configuration. */
@Data
@Component
public class GraphRagExecutionProperties {
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private GraphRagExecutionConfigurationPort configurationPort;
    private Integer workerThreads;
    private Integer queueCapacity;
    private Integer documentConcurrency;
    private Integer maxBatchAttempts;
    private Long retryBackoffMillis;
    private Long documentBudgetMillis;
    private Long toolBudgetMillis;
    /** Combined transport and cleanup safety margin deducted from the document deadline. */
    private Long reserveMillis;
    private Integer maxSplitDepth;
    private Integer maxSplits;
    private Integer maxTimeoutSplitsPerSource;
    private Integer maxBatches;
    private Long maxCandidateBytes;
    private Integer maxObservationBytes;

    public int getWorkerThreads() { return required(managed().workerThreads, "graphRag.execution.workerThreads"); }
    public int getQueueCapacity() { return required(managed().queueCapacity, "graphRag.execution.queueCapacity"); }
    public int getDocumentConcurrency() { return required(managed().documentConcurrency, "graphRag.execution.documentConcurrency"); }
    public int getMaxBatchAttempts() { return required(managed().maxBatchAttempts, "graphRag.execution.maxBatchAttempts"); }
    public long getRetryBackoffMillis() { return required(managed().retryBackoffMillis, "graphRag.execution.retryBackoffMillis"); }
    public long getDocumentBudgetMillis() { return required(managed().documentBudgetMillis, "graphRag.execution.documentBudgetMillis"); }
    public long getToolBudgetMillis() { return required(managed().toolBudgetMillis, "graphRag.execution.toolBudgetMillis"); }
    public long getReserveMillis() { return required(managed().reserveMillis, "graphRag.execution.reserveMillis"); }
    public int getMaxSplitDepth() { return required(managed().maxSplitDepth, "graphRag.execution.maxSplitDepth"); }
    public int getMaxSplits() { return required(managed().maxSplits, "graphRag.execution.maxSplits"); }
    public int getMaxTimeoutSplitsPerSource() { return required(managed().maxTimeoutSplitsPerSource, "graphRag.execution.maxTimeoutSplitsPerSource"); }
    public int getMaxBatches() { return required(managed().maxBatches, "graphRag.execution.maxBatches"); }
    public long getMaxCandidateBytes() { return required(managed().maxCandidateBytes, "graphRag.execution.maxCandidateBytes"); }
    public int getMaxObservationBytes() { return required(managed().maxObservationBytes, "graphRag.execution.maxObservationBytes"); }
    public void setRetryBackoffMillis(long retryBackoffMillis) { this.retryBackoffMillis = retryBackoffMillis; }
    public void setDocumentBudgetMillis(long documentBudgetMillis) { this.documentBudgetMillis = documentBudgetMillis; }
    public void setToolBudgetMillis(long toolBudgetMillis) { this.toolBudgetMillis = toolBudgetMillis; }
    public void setReserveMillis(long reserveMillis) { this.reserveMillis = reserveMillis; }
    public void setMaxCandidateBytes(long maxCandidateBytes) { this.maxCandidateBytes = maxCandidateBytes; }

    private GraphRagExecutionProperties managed() {
        if (configurationPort == null) return this;
        GraphRagExecutionProperties configured = configurationPort.current();
        if (configured == null || configured == this) {
            throw new IllegalStateException("Missing required database configuration: graphRag.execution");
        }
        return configured;
    }

    public void validate() {
        GraphRagExecutionProperties configured = managed();
        int workerThreads = required(configured.workerThreads, "graphRag.execution.workerThreads");
        int queueCapacity = required(configured.queueCapacity, "graphRag.execution.queueCapacity");
        int documentConcurrency = required(configured.documentConcurrency, "graphRag.execution.documentConcurrency");
        int maxBatchAttempts = required(configured.maxBatchAttempts, "graphRag.execution.maxBatchAttempts");
        long retryBackoffMillis = required(configured.retryBackoffMillis, "graphRag.execution.retryBackoffMillis");
        long documentBudgetMillis = required(configured.documentBudgetMillis, "graphRag.execution.documentBudgetMillis");
        long toolBudgetMillis = required(configured.toolBudgetMillis, "graphRag.execution.toolBudgetMillis");
        long reserveMillis = required(configured.reserveMillis, "graphRag.execution.reserveMillis");
        int maxSplitDepth = required(configured.maxSplitDepth, "graphRag.execution.maxSplitDepth");
        int maxSplits = required(configured.maxSplits, "graphRag.execution.maxSplits");
        int maxTimeoutSplitsPerSource = required(configured.maxTimeoutSplitsPerSource, "graphRag.execution.maxTimeoutSplitsPerSource");
        int maxBatches = required(configured.maxBatches, "graphRag.execution.maxBatches");
        long maxCandidateBytes = required(configured.maxCandidateBytes, "graphRag.execution.maxCandidateBytes");
        int maxObservationBytes = required(configured.maxObservationBytes, "graphRag.execution.maxObservationBytes");
        if (workerThreads < 1 || workerThreads > 64 || queueCapacity < 1 || queueCapacity > 4096
                || documentConcurrency < 1 || documentConcurrency > workerThreads || maxBatchAttempts < 1
                || maxBatchAttempts > 10 || retryBackoffMillis < 0 || retryBackoffMillis > 60000
                || documentBudgetMillis < 1 || documentBudgetMillis > 14400000
                || toolBudgetMillis < 1 || toolBudgetMillis > 300000
                || reserveMillis < 1 || reserveMillis > 120000
                || documentBudgetMillis <= reserveMillis
                || maxSplitDepth < 0 || maxSplitDepth > 10 || maxSplits < 0 || maxSplits > 1024
                || maxTimeoutSplitsPerSource < 0 || maxTimeoutSplitsPerSource > 10
                || maxBatches < 1 || maxBatches > 4096
                || maxCandidateBytes < 1 || maxCandidateBytes > 268435456
                || maxObservationBytes < 4096 || maxObservationBytes > 32768) {
            throw new IllegalArgumentException("Invalid app.graph-rag.execution limits");
        }
    }

    private <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalStateException("Missing required database configuration: " + key);
        }
        return value;
    }
}
