package org.smartledge.ai.ragtools.adapter;

import org.smartledge.ai.rag.runtime.port.RerankPort;
import org.smartledge.ai.ragtools.client.RagToolsClient;
import org.smartledge.ai.ragtools.model.RagToolsRerankRequest;
import org.smartledge.ai.ragtools.model.RagToolsRerankResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;

/** Maps the runtime-owned rerank interface to the Python rag-tools HTTP protocol. */
@Component
public class RagToolsRerankAdapter implements RerankPort {

    private static final String MODEL = "rag-tools";

    private final RagToolsClient client;

    public RagToolsRerankAdapter(RagToolsClient client) {
        this.client = client;
    }

    @Override
    public Response rerank(Request request) {
        RagToolsRerankRequest transportRequest = new RagToolsRerankRequest(
            request.query(),
            request.candidates().stream()
                .map(candidate -> new RagToolsRerankRequest.Candidate(
                    candidate.id(),
                    candidate.text(),
                    candidate.metadata() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(candidate.metadata())
                ))
                .toList(),
            request.topK()
        );
        RagToolsRerankResponse transportResponse = client.rerank(transportRequest);
        if (transportResponse == null) {
            return null;
        }
        List<Result> results = transportResponse.getResults() == null
            ? null
            : transportResponse.getResults().stream()
                .map(result -> result == null
                    ? new Result(null, null, null)
                    : new Result(result.getId(), result.getScore(), result.getRank()))
                .toList();
        return new Response(MODEL, results);
    }
}
