package org.smartledge.ai.ragtools.model;

import lombok.Data;

@Data
public class RagToolsHealthResponse {

    private String status;

    private String service;

    private String version;
}
