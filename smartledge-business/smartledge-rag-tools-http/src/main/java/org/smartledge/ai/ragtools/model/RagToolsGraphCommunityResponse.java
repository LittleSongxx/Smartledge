package org.smartledge.ai.ragtools.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagToolsGraphCommunityResponse {

    private String method;

    private Map<String, String> membership = new LinkedHashMap<>();
}
