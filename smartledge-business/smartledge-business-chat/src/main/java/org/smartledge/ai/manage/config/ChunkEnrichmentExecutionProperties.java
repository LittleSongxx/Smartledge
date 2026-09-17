package org.smartledge.ai.manage.config;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.smartledge.ai.manage.service.SystemConfigProvider;

@Data
@Component
public class ChunkEnrichmentExecutionProperties {
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private SystemConfigProvider systemConfigProvider;
    private Integer workerThreads;
    private Integer queueCapacity;
    private Integer documentConcurrency;
    private Long batchTimeoutMillis;
    private Long documentBudgetMillis;
    private Long pollMillis;

    public int getWorkerThreads() { return required(managed().workerThreads, "chunkEnrichment.execution.workerThreads"); }
    public int getQueueCapacity() { return required(managed().queueCapacity, "chunkEnrichment.execution.queueCapacity"); }
    public int getDocumentConcurrency() { return required(managed().documentConcurrency, "chunkEnrichment.execution.documentConcurrency"); }
    public long getBatchTimeoutMillis() { return required(managed().batchTimeoutMillis, "chunkEnrichment.execution.batchTimeoutMillis"); }
    public long getDocumentBudgetMillis() { return required(managed().documentBudgetMillis, "chunkEnrichment.execution.documentBudgetMillis"); }
    public long getPollMillis() { return required(managed().pollMillis, "chunkEnrichment.execution.pollMillis"); }
    public void setBatchTimeoutMillis(long batchTimeoutMillis) { this.batchTimeoutMillis = batchTimeoutMillis; }
    public void setDocumentBudgetMillis(long documentBudgetMillis) { this.documentBudgetMillis = documentBudgetMillis; }
    public void setPollMillis(long pollMillis) { this.pollMillis = pollMillis; }

    private ChunkEnrichmentExecutionProperties managed() {
        if (systemConfigProvider == null) return this;
        var snapshot = systemConfigProvider.currentSnapshot();
        if (snapshot == null || snapshot.getChunkEnrichmentExecution() == null) {
            throw new IllegalStateException("Missing required database configuration: chunkEnrichment.execution");
        }
        ChunkEnrichmentExecutionProperties configured = new ChunkEnrichmentExecutionProperties();
        configured.workerThreads = snapshot.getChunkEnrichmentExecution().getWorkerThreads();
        configured.queueCapacity = snapshot.getChunkEnrichmentExecution().getQueueCapacity();
        configured.documentConcurrency = snapshot.getChunkEnrichmentExecution().getDocumentConcurrency();
        configured.batchTimeoutMillis = snapshot.getChunkEnrichmentExecution().getBatchTimeoutMillis();
        configured.documentBudgetMillis = snapshot.getChunkEnrichmentExecution().getDocumentBudgetMillis();
        configured.pollMillis = snapshot.getChunkEnrichmentExecution().getPollMillis();
        return configured;
    }

    public void validate() {
        ChunkEnrichmentExecutionProperties configured = managed();
        int workerThreads = required(configured.workerThreads, "chunkEnrichment.execution.workerThreads");
        int queueCapacity = required(configured.queueCapacity, "chunkEnrichment.execution.queueCapacity");
        int documentConcurrency = required(configured.documentConcurrency, "chunkEnrichment.execution.documentConcurrency");
        long batchTimeoutMillis = required(configured.batchTimeoutMillis, "chunkEnrichment.execution.batchTimeoutMillis");
        long documentBudgetMillis = required(configured.documentBudgetMillis, "chunkEnrichment.execution.documentBudgetMillis");
        long pollMillis = required(configured.pollMillis, "chunkEnrichment.execution.pollMillis");
        if (workerThreads < 1 || workerThreads > 64 || queueCapacity < 1 || queueCapacity > 4096
                || documentConcurrency < 1 || documentConcurrency > workerThreads || batchTimeoutMillis < 1
                || batchTimeoutMillis > 300000 || documentBudgetMillis < batchTimeoutMillis
                || documentBudgetMillis > 3600000 || pollMillis < 1 || pollMillis > 1000) {
            throw new IllegalArgumentException("Invalid app.chunk-enrichment.execution limits");
        }
    }

    private <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalStateException("Missing required database configuration: " + key);
        }
        return value;
    }
}
