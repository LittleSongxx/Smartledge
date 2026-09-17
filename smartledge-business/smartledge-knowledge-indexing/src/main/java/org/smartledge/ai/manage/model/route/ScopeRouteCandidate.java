package org.smartledge.ai.manage.model.route;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @description: 模型对象
 * @author: Song
 **/
@Data
@NoArgsConstructor
public class ScopeRouteCandidate {

    private Long scopeId;

    private String scopeName;

    private BigDecimal score;

    private String reason;

    private String source = "";

    private Map<String, BigDecimal> features = new LinkedHashMap<>();

    public ScopeRouteCandidate(Long scopeId, String scopeName, BigDecimal score, String reason) {
        this(scopeId, scopeName, score, reason, "", new LinkedHashMap<>());
    }

    public ScopeRouteCandidate(Long scopeId,
                               String scopeName,
                               BigDecimal score,
                               String reason,
                               String source,
                               Map<String, BigDecimal> features) {
        this.scopeId = scopeId;
        this.scopeName = scopeName;
        this.score = score;
        this.reason = reason;
        this.source = source;
        this.features = features == null ? new LinkedHashMap<>() : new LinkedHashMap<>(features);
    }
}
