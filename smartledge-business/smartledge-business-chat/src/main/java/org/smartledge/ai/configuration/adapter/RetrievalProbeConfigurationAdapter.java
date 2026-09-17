package org.smartledge.ai.configuration.adapter;

import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeConfigurationPort;
import org.smartledge.ai.chatagent.evaluation.probe.RetrievalProbeProperties;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.stereotype.Service;

@Service
public class RetrievalProbeConfigurationAdapter implements RetrievalProbeConfigurationPort {

    private final SystemConfigProvider provider;

    public RetrievalProbeConfigurationAdapter(SystemConfigProvider provider) {
        this.provider = provider;
    }

    @Override
    public RetrievalProbeProperties current() {
        var source = provider.currentSnapshot().getRetrievalProbe();
        var target = new RetrievalProbeProperties();
        target.setEnabled(source.isEnabled());
        target.setRequestsPerMinute(source.getRequestsPerMinute());
        target.setMaxQueryLength(source.getMaxQueryLength());
        target.setMaxResultCount(source.getMaxResultCount());
        return target;
    }
}
