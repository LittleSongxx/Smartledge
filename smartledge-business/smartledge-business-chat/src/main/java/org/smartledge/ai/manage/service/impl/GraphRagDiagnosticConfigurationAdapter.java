package org.smartledge.ai.manage.service.impl;
import org.smartledge.ai.knowledge.augmentation.config.GraphRagDiagnosticProperties;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagDiagnosticConfigurationPort;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.stereotype.Service;
@Service
public class GraphRagDiagnosticConfigurationAdapter implements GraphRagDiagnosticConfigurationPort {
    private final SystemConfigProvider provider;
    public GraphRagDiagnosticConfigurationAdapter(SystemConfigProvider provider) { this.provider = provider; }
    @Override public GraphRagDiagnosticProperties current() {
        var s = provider.currentSnapshot().getGraphRagDiagnostics(); var t = new GraphRagDiagnosticProperties();
        t.setEnabled(s.isEnabled()); t.setDirectory(s.getDirectory()); t.setMaxTextChars(s.getMaxTextChars()); t.setMaxFileBytes(s.getMaxFileBytes()); t.setMaxFiles(s.getMaxFiles()); return t;
    }
}
