package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.knowledge.augmentation.config.GraphRagExecutionProperties;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagExecutionConfigurationPort;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.stereotype.Service;

@Service
public class GraphRagExecutionConfigurationAdapter implements GraphRagExecutionConfigurationPort {
    private final SystemConfigProvider provider;
    public GraphRagExecutionConfigurationAdapter(SystemConfigProvider provider) { this.provider = provider; }
    @Override public GraphRagExecutionProperties current() {
        SystemConfigSnapshot.GraphRagExecutionOptions s = provider.currentSnapshot().getGraphRagExecution();
        GraphRagExecutionProperties t = new GraphRagExecutionProperties();
        t.setWorkerThreads(s.getWorkerThreads()); t.setQueueCapacity(s.getQueueCapacity()); t.setDocumentConcurrency(s.getDocumentConcurrency());
        t.setMaxBatchAttempts(s.getMaxBatchAttempts()); t.setRetryBackoffMillis(s.getRetryBackoffMillis()); t.setDocumentBudgetMillis(s.getDocumentBudgetMillis());
        t.setToolBudgetMillis(s.getToolBudgetMillis()); t.setReserveMillis(s.getReserveMillis()); t.setMaxSplitDepth(s.getMaxSplitDepth());
        t.setMaxSplits(s.getMaxSplits()); t.setMaxTimeoutSplitsPerSource(s.getMaxTimeoutSplitsPerSource()); t.setMaxBatches(s.getMaxBatches());
        t.setMaxCandidateBytes(s.getMaxCandidateBytes()); t.setMaxObservationBytes(s.getMaxObservationBytes());
        return t;
    }
}
