package org.smartledge.ai.knowledge.augmentation.config;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagDiagnosticConfigurationPort;

@Data
@Component
public class GraphRagDiagnosticProperties {
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private GraphRagDiagnosticConfigurationPort configurationPort;
    @Getter(AccessLevel.NONE)
    private Boolean enabled;
    private String directory;
    private Integer maxTextChars;
    private Integer maxFileBytes;
    private Integer maxFiles;

    public boolean isEnabled() { return required(managed().enabled, "graphRag.diagnostics.enabled"); }
    public String getDirectory() { return required(managed().directory, "graphRag.diagnostics.directory"); }
    public int getMaxTextChars() { return required(managed().maxTextChars, "graphRag.diagnostics.maxTextChars"); }
    public int getMaxFileBytes() { return required(managed().maxFileBytes, "graphRag.diagnostics.maxFileBytes"); }
    public int getMaxFiles() { return required(managed().maxFiles, "graphRag.diagnostics.maxFiles"); }

    private GraphRagDiagnosticProperties managed() {
        if (configurationPort == null) return this;
        GraphRagDiagnosticProperties configured = configurationPort.current();
        if (configured == null || configured == this) {
            throw new IllegalStateException("Missing required database configuration: graphRag.diagnostics");
        }
        return configured;
    }

    public void validate() {
        GraphRagDiagnosticProperties configured = managed();
        String directory = required(configured.directory, "graphRag.diagnostics.directory");
        int maxTextChars = required(configured.maxTextChars, "graphRag.diagnostics.maxTextChars");
        int maxFileBytes = required(configured.maxFileBytes, "graphRag.diagnostics.maxFileBytes");
        int maxFiles = required(configured.maxFiles, "graphRag.diagnostics.maxFiles");
        if (directory == null || directory.isBlank() || maxTextChars < 1
                || maxTextChars > 16384 || maxFileBytes < 1024
                || maxFileBytes > 16777216 || maxFiles < 1 || maxFiles > 100) {
            throw new IllegalArgumentException("Invalid app.graph-rag.diagnostics limits");
        }
    }

    private <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalStateException("Missing required database configuration: " + key);
        }
        return value;
    }
}
