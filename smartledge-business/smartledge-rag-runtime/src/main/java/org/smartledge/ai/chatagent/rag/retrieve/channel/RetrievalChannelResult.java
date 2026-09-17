package org.smartledge.ai.chatagent.rag.retrieve.channel;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.List;
import java.util.Map;

/**
 * @description: 结果对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
public class RetrievalChannelResult {

    private String channelName;

    private List<RetrievalDocument> documents;

    private Map<String, Object> configSnapshot = Map.of();

    private String errorMessage = "";

    public RetrievalChannelResult(String channelName, List<RetrievalDocument> documents) {
        this(channelName, documents, Map.of(), "");
    }

    public RetrievalChannelResult(String channelName,
                                  List<RetrievalDocument> documents,
                                  Map<String, Object> configSnapshot,
                                  String errorMessage) {
        this.channelName = channelName;
        this.documents = documents;
        this.configSnapshot = configSnapshot == null ? Map.of() : Map.copyOf(configSnapshot);
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }
}
