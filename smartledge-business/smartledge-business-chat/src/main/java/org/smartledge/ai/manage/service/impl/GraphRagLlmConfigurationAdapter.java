package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.knowledge.augmentation.config.GraphRagExtractionOptions;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagLlmConfigurationPort;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Composition-root adapter for dynamic GraphRAG LLM advisor configuration.
 */
@Service
public class GraphRagLlmConfigurationAdapter implements GraphRagLlmConfigurationPort {

    private final ObjectProvider<SystemConfigProvider> provider;

    public GraphRagLlmConfigurationAdapter(ObjectProvider<SystemConfigProvider> provider) {
        this.provider = provider;
    }

    @Override
    public GraphRagExtractionOptions extraction() {
        SystemConfigSnapshot snapshot = requireSnapshot();
        if (snapshot.getGraphRagExtraction() == null) {
            throw new IllegalStateException("系统配置缺少 GraphRAG extraction 配置");
        }
        SystemConfigSnapshot.GraphRagExtractionOptions source = snapshot.getGraphRagExtraction();
        GraphRagExtractionOptions target = new GraphRagExtractionOptions();
        target.setBatchChunkLimit(source.getBatchChunkLimit());
        target.setInputTokenBudget(source.getInputTokenBudget());
        target.setModelContextTokens(source.getModelContextTokens());
        target.setOutputReserve(source.getOutputReserve());
        target.setPromptReserve(source.getPromptReserve());
        target.setModelResponseMaxBytes(source.getModelResponseMaxBytes());
        target.setMaxEntityNameChars(source.getMaxEntityNameChars());
        target.setMaxDocumentBytes(source.getMaxDocumentBytes());
        target.setMaxQuoteChars(source.getMaxQuoteChars());
        target.setMaxReasonChars(source.getMaxReasonChars());
        target.setMaxUnitTextChars(source.getMaxUnitTextChars());
        return target;
    }

    @Override
    public boolean entityResolutionEnabled() {
        SystemConfigSnapshot snapshot = requireSnapshot();
        if (snapshot.getGraphRagEntityResolution() == null) {
            throw new IllegalStateException("系统配置缺少 GraphRAG entity-resolution 配置");
        }
        return snapshot.getGraphRagEntityResolution().isEnabled();
    }

    @Override
    public boolean queryPlanEnabled() {
        SystemConfigSnapshot snapshot = requireSnapshot();
        if (snapshot.getRag() == null || snapshot.getRag().getGraphRagQueryPlan() == null) {
            throw new IllegalStateException("系统配置缺少 GraphRAG query-plan 配置");
        }
        return snapshot.getRag().getGraphRagQueryPlan().isEnabled();
    }

    private SystemConfigSnapshot snapshot() {
        SystemConfigProvider systemConfigProvider = provider == null ? null : provider.getIfAvailable();
        return systemConfigProvider == null ? null : systemConfigProvider.currentSnapshot();
    }

    private SystemConfigSnapshot requireSnapshot() {
        SystemConfigSnapshot snapshot = snapshot();
        if (snapshot == null) {
            throw new IllegalStateException("系统配置提供方不可用，拒绝使用 GraphRAG 默认配置");
        }
        return snapshot;
    }
}
