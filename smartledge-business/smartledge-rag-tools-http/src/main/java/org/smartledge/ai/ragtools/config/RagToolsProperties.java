package org.smartledge.ai.ragtools.config;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.smartledge.ai.ragtools.port.RagToolsConfigurationPort;

@Data
@Component
public class RagToolsProperties {
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private RagToolsConfigurationPort configurationPort;

    private String baseUrl;

    private Integer connectTimeoutMs;

    private Integer documentParseReadTimeoutMs;

    private Integer graphExtractReadTimeoutMs;

    private Integer graphExtractMaxResponseBytes;

    private Integer raptorBuildReadTimeoutMs;

    public String getBaseUrl() { return required(managed().baseUrl, "ragTools.baseUrl"); }
    public int getConnectTimeoutMs() { return required(managed().connectTimeoutMs, "ragTools.connectTimeoutMs"); }
    public int getDocumentParseReadTimeoutMs() { return required(managed().documentParseReadTimeoutMs, "ragTools.documentParseReadTimeoutMs"); }
    public int getGraphExtractReadTimeoutMs() { return required(managed().graphExtractReadTimeoutMs, "ragTools.graphExtractReadTimeoutMs"); }
    public int getGraphExtractMaxResponseBytes() { return required(managed().graphExtractMaxResponseBytes, "ragTools.graphExtractionResponseMaxBytes"); }
    public int getRaptorBuildReadTimeoutMs() { return required(managed().raptorBuildReadTimeoutMs, "ragTools.raptorBuildReadTimeoutMs"); }

    private RagToolsProperties managed() {
        if (configurationPort == null) return this;
        RagToolsProperties configured = configurationPort.current();
        if (configured == null || configured == this) {
            throw new IllegalStateException("Missing required database configuration: ragTools");
        }
        return configured;
    }

    private <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalStateException("Missing required database configuration: " + key);
        }
        return value;
    }
}
