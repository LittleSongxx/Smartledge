package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.knowledge.augmentation.config.GraphRagBuildProperties;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagBuildConfigurationPort;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Composition-root adapter for database-backed GraphRAG build configuration.
 */
@Service
public class GraphRagBuildConfigurationAdapter implements GraphRagBuildConfigurationPort {

    private final ObjectProvider<SystemConfigProvider> provider;

    public GraphRagBuildConfigurationAdapter(ObjectProvider<SystemConfigProvider> provider) {
        this.provider = provider;
    }

    @Override
    public GraphRagBuildProperties current() {
        SystemConfigProvider systemConfigProvider = provider == null ? null : provider.getIfAvailable();
        if (systemConfigProvider == null) {
            return null;
        }
        SystemConfigSnapshot snapshot = systemConfigProvider.currentSnapshot();
        return snapshot == null ? null : snapshot.getGraphRagBuild();
    }
}
