package org.smartledge.ai.rag.runtime.model.route;

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
public class TopicRouteCandidate {

    private Long topicId;

    private String topicName;

    private Long scopeId;

    private BigDecimal score;

    private String reason;

    private String source = "";

    private Map<String, BigDecimal> features = new LinkedHashMap<>();

    public TopicRouteCandidate(Long topicId, String topicName, Long scopeId, BigDecimal score, String reason) {
        this(topicId, topicName, scopeId, score, reason, "", new LinkedHashMap<>());
    }

    public TopicRouteCandidate(Long topicId,
                               String topicName,
                               Long scopeId,
                               BigDecimal score,
                               String reason,
                               String source,
                               Map<String, BigDecimal> features) {
        this.topicId = topicId;
        this.topicName = topicName;
        this.scopeId = scopeId;
        this.score = score;
        this.reason = reason;
        this.source = source;
        this.features = features == null ? new LinkedHashMap<>() : new LinkedHashMap<>(features);
    }
}
