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
public class DocumentRouteCandidate {

    private String documentId;

    private String documentName;

    private String lastIndexTaskId;

    private BigDecimal score;

    private String reason;

    private String source = "";

    private Map<String, BigDecimal> features = new LinkedHashMap<>();

    public DocumentRouteCandidate(String documentId,
                                  String documentName,
                                  String lastIndexTaskId,
                                  BigDecimal score,
                                  String reason) {
        this(documentId, documentName, lastIndexTaskId, score, reason, "", new LinkedHashMap<>());
    }

    public DocumentRouteCandidate(String documentId,
                                  String documentName,
                                  String lastIndexTaskId,
                                  BigDecimal score,
                                  String reason,
                                  String source,
                                  Map<String, BigDecimal> features) {
        this.documentId = documentId;
        this.documentName = documentName;
        this.lastIndexTaskId = lastIndexTaskId;
        this.score = score;
        this.reason = reason;
        this.source = source;
        this.features = features == null ? new LinkedHashMap<>() : new LinkedHashMap<>(features);
    }
}
