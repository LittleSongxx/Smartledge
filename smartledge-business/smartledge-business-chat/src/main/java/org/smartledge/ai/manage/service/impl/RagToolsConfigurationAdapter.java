package org.smartledge.ai.manage.service.impl;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.smartledge.ai.ragtools.config.RagToolsProperties;
import org.smartledge.ai.ragtools.port.RagToolsConfigurationPort;
import org.springframework.stereotype.Service;
@Service
public class RagToolsConfigurationAdapter implements RagToolsConfigurationPort {
    private final SystemConfigProvider provider;
    public RagToolsConfigurationAdapter(SystemConfigProvider provider) { this.provider = provider; }
    @Override public RagToolsProperties current() {
        var s = provider.currentSnapshot().getRagTools(); var t = new RagToolsProperties();
        t.setBaseUrl(s.getBaseUrl()); t.setConnectTimeoutMs(s.getConnectTimeoutMs()); t.setDocumentParseReadTimeoutMs(s.getDocumentParseReadTimeoutMs());
        t.setGraphExtractReadTimeoutMs(s.getGraphExtractReadTimeoutMs()); t.setGraphExtractMaxResponseBytes(s.getGraphExtractionResponseMaxBytes()); t.setRaptorBuildReadTimeoutMs(s.getRaptorBuildReadTimeoutMs()); return t;
    }
}
