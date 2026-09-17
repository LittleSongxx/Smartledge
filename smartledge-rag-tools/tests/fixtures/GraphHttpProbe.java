import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.ragtools.client.RagToolsClient;
import org.smartledge.ai.ragtools.config.RagToolsProperties;
import org.smartledge.ai.ragtools.model.RagToolsGraphExtractRequest;
import org.smartledge.ai.ragtools.model.RagToolsGraphExtractResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GraphHttpProbe {
    public static void main(String[] args) {
        RagToolsProperties properties = new RagToolsProperties();
        properties.setBaseUrl(args[0]);
        properties.setConnectTimeoutMs(3000);
        properties.setDocumentParseReadTimeoutMs(600000);
        properties.setGraphExtractReadTimeoutMs(5000);
        properties.setGraphExtractMaxResponseBytes(16777216);
        properties.setRaptorBuildReadTimeoutMs(60000);
        RagToolsClient client = new RagToolsClient(properties, new ObjectMapper());
        try {
            RagToolsGraphExtractRequest request = new RagToolsGraphExtractRequest();
            request.setSchemaVersion("graph-candidates.v3");
            request.setOperation("plan");
            request.setSourceParseTaskId(2498400033909530625L);
            request.setInputFingerprint("a".repeat(64));
            request.setBudgetMillis(35000L);
            request.setOptions(Map.of());
            List<RagToolsGraphExtractRequest.Chunk> chunks = new ArrayList<>();
            for (int index = 0; index < 228; index++) {
                RagToolsGraphExtractRequest.Chunk chunk = new RagToolsGraphExtractRequest.Chunk();
                chunk.setChunkId(2498400033909540000L + index);
                chunk.setText("Alpha calls Beta. ".repeat(100));
                chunks.add(chunk);
            }
            request.setChunks(chunks);
            RagToolsGraphExtractResponse response = client.extractGraph(request);
            if (!Integer.valueOf(228).equals(response.getMetadata().get("receivedChunks"))) {
                throw new AssertionError("Uvicorn did not receive the complete Java request");
            }
            System.out.println("JAVA_UVICORN_OK chunks=228");
        }
        finally {
            client.closeGraphTransport();
        }
    }
}
