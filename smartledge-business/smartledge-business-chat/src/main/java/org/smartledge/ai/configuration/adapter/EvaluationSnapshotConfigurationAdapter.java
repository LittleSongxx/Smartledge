package org.smartledge.ai.configuration.adapter;

import org.smartledge.ai.chatagent.evaluation.EvaluationSnapshotConfigurationPort;
import org.smartledge.ai.chatagent.evaluation.EvaluationSnapshotProperties;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.stereotype.Service;

@Service
public class EvaluationSnapshotConfigurationAdapter implements EvaluationSnapshotConfigurationPort {

    private final SystemConfigProvider provider;

    public EvaluationSnapshotConfigurationAdapter(SystemConfigProvider provider) {
        this.provider = provider;
    }

    @Override
    public EvaluationSnapshotProperties current() {
        var source = provider.currentSnapshot().getEvaluationSnapshot();
        var target = new EvaluationSnapshotProperties();
        target.setEnabled(source.isEnabled());
        target.setMaxBatchSize(source.getMaxBatchSize());
        target.setRequestsPerMinute(source.getRequestsPerMinute());
        target.setPromptVersion(source.getPromptVersion());
        return target;
    }
}
