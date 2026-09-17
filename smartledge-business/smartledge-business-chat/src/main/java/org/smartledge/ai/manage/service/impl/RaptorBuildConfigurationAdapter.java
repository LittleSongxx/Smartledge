package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.knowledge.augmentation.port.RaptorBuildConfigurationPort;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Composition-root adapter for RAPTOR embedding batch configuration.
 */
@Service
public class RaptorBuildConfigurationAdapter implements RaptorBuildConfigurationPort {

    private final DocumentManageProperties properties;

    @Autowired(required = false)
    private SystemConfigProvider systemConfigProvider;

    public RaptorBuildConfigurationAdapter(DocumentManageProperties properties) {
        this.properties = properties;
    }

    @Override
    public int embeddingBatchSize() {
        if (properties == null || properties.getIndexBuild() == null
            || properties.getIndexBuild().getEmbeddingBatchSize() == null) {
            throw new IllegalStateException("系统配置缺少 RAPTOR 向量化批次大小");
        }
        int value = properties.getIndexBuild().getEmbeddingBatchSize();
        if (value < 1 || value > 10) {
            throw new IllegalStateException("RAPTOR 向量化批次大小超出允许范围");
        }
        return value;
    }

    public int llmConcurrency() {
        if (systemConfigProvider == null || systemConfigProvider.currentSnapshot() == null) {
            throw new IllegalStateException("系统配置提供方不可用，拒绝使用 RAPTOR 默认并发");
        }
        return systemConfigProvider.currentSnapshot().getRaptorLlmConcurrency();
    }
}
